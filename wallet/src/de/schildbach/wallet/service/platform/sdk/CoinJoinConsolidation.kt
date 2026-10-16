package de.schildbach.wallet.service.platform.sdk

import kotlinx.coroutines.flow.first
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.dashfoundation.dashsdk.persistence.entities.CoreAddressEntity
import org.dashfoundation.dashsdk.persistence.entities.TxoEntity
import org.json.JSONObject

internal const val COIN_JOIN_ASSET_LOCK_INPUT_LIMIT = 100
internal const val COIN_JOIN_CONSOLIDATION_INPUT_LIMIT = 500

internal data class CoinJoinConsolidationState(
    val walletId: String,
    val destination: String,
    val phase: String = READY,
    val txid: String = "",
    val regularDestination: String = ""
) {
    val action: MixedFundsMigrationAction get() = if (regularDestination.isEmpty())
        MixedFundsMigrationAction.SHIELD else MixedFundsMigrationAction.COMBINE
    fun encode(): String = JSONObject().put("walletId", walletId).put("destination", destination)
        .put("phase", phase).put("txid", txid).put("regularDestination", regularDestination).toString()

    companion object {
        const val READY = "ready"
        const val WAITING = "waiting"
        /** About to call the shield funding; the native call has NOT been reached. */
        const val SHIELD_PREPARING = "shield_preparing"
        /** The shield funding call was reached: a lock may be out, never build another. */
        const val SHIELDING = "shielding"
        const val TRANSFERRING = "transferring"
        fun decode(raw: String): CoinJoinConsolidationState {
            val json = JSONObject(raw)
            return CoinJoinConsolidationState(json.getString("walletId"), json.getString("destination"),
                json.getString("phase"), json.getString("txid"), json.optString("regularDestination", ""))
        }
    }
}

internal fun unusedCoinJoinAddress(rows: List<CoreAddressEntity>, accountId: Long): String? =
    rows.filter { it.accountId == accountId && it.poolTypeTag == 0 && !it.isUsed && it.balance == 0L }
        .minByOrNull { it.addressIndex }?.address

internal data class CoinJoinConsolidationSnapshot(
    val walletId: String,
    val coins: List<TxoEntity>,
    val destination: String?
) {
    val needsConsolidation: Boolean get() = coins.size > COIN_JOIN_ASSET_LOCK_INPUT_LIMIT
    val drain: Boolean get() = coins.size <= COIN_JOIN_CONSOLIDATION_INPUT_LIMIT
    // Conservative P2PKH size allowance at 2 duffs/byte, including two outputs.
    // The signed transaction is checked again before anything is broadcast.
    fun batchAmount(): Long = if (drain) 1L else
        coins.sortedByDescending { it.amount }.take(COIN_JOIN_CONSOLIDATION_INPUT_LIMIT)
            .sumOf { it.amount } - (COIN_JOIN_CONSOLIDATION_INPUT_LIMIT * 160L + 100L) * 2L
}

internal suspend fun coinJoinConsolidationSnapshot(service: DashSdkService): CoinJoinConsolidationSnapshot? {
    val manager = service.walletManagerOrNull() ?: return null
    val walletId = manager.wallets.value.keys.singleOrNull() ?: return null
    val walletBytes = Utils.HEX.decode(walletId)
    val db = service.databaseOrNull() ?: return null
    val account = db.accountDao().getByKey(walletBytes, ACCOUNT_TYPE_TAG_COIN_JOIN, 0).singleOrNull()
        ?: return null
    val addresses = db.coreAddressDao().observeByAccount(account.id).first()
    val owned = addresses.map { it.address }.toSet()
    val coins = db.txoDao().observeUnspentByWallet(walletBytes).first()
        .filter { it.accountId == account.id || it.address in owned }
    // Do not act on a partially persisted snapshot, an unsettled self-spend,
    // or coins already claimed by a different transaction.
    if (coins.any { it.isLocked || it.spendingTxid != null || (!it.isConfirmed && !it.isInstantLocked) }) return null
    val confirmed = parseCoinJoinConfirmedDuffs(manager.accountBalances(walletBytes)) ?: return null
    if (coins.sumOf { it.amount } != confirmed) return null
    return CoinJoinConsolidationSnapshot(walletId, coins, unusedCoinJoinAddress(addresses, account.id))
}

internal fun validateCoinJoinConsolidation(
    raw: ByteArray, snapshot: CoinJoinConsolidationSnapshot, destination: String, params: NetworkParameters,
    minimumInputs: Int = 2
): String {
    val tx = Transaction(params, raw)
    require(tx.inputs.size in minimumInputs..COIN_JOIN_CONSOLIDATION_INPUT_LIMIT) { "Unexpected consolidation input count" }
    val owned = snapshot.coins.map { Utils.HEX.encode(it.outpoint) }.toSet()
    require(tx.inputs.all { Utils.HEX.encode(it.outpoint.bitcoinSerialize()) in owned }) { "Unexpected consolidation input" }
    val script = org.bitcoinj.script.ScriptBuilder.createOutputScript(Address.fromBase58(params, destination)).program
    require(tx.outputs.isNotEmpty() && tx.outputs.size <= 2 && tx.outputs.all { it.scriptBytes.contentEquals(script) }) {
        "Consolidation must pay only the selected CoinJoin address"
    }
    return tx.txId.toString()
}
