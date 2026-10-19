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
    val regularDestination: String = "",
    /**
     * Hex of the signed [txid] transaction, written in the SAME checkpoint
     * write as [txid], before broadcast, so a process that dies before the
     * native broadcast can replay the exact bytes. Empty on states saved by
     * older builds (observation only, as before) and outside WAITING/TRANSFERRING.
     */
    val rawTx: String = ""
) {
    val action: MixedFundsMigrationAction get() = if (regularDestination.isEmpty())
        MixedFundsMigrationAction.SHIELD else MixedFundsMigrationAction.COMBINE
    fun encode(): String = JSONObject().put("walletId", walletId).put("destination", destination)
        .put("phase", phase).put("txid", txid).put("regularDestination", regularDestination)
        .apply { if (rawTx.isNotEmpty()) put("rawTx", rawTx) }.toString()

    /**
     * The persisted signed bytes for replay, or null when there are none
     * (legacy state) or they do not hash to [txid] — never broadcast bytes
     * that are not the recorded transaction.
     */
    fun replayBytesOrNull(params: NetworkParameters): ByteArray? {
        if (rawTx.isEmpty() || txid.isEmpty()) return null
        return try {
            val bytes = Utils.HEX.decode(rawTx.lowercase())
            bytes.takeIf { Transaction(params, it).txId.toString().equals(txid, ignoreCase = true) }
        } catch (e: Exception) {
            null
        }
    }

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
                json.getString("phase"), json.getString("txid"), json.optString("regularDestination", ""),
                json.optString("rawTx", ""))
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

/**
 * @param persistedInstantLocks the subset of the given lowercase display-hex
 *   txids with app-persisted InstantSend lock evidence (`instant_send_locks`).
 *   The SDK mirror never sets `txos.isInstantLocked` (see
 *   [de.schildbach.wallet.database.entity.InstantSendLockEntry]), so an
 *   IS-locked, not-yet-mined output is final only through this evidence.
 */
internal suspend fun coinJoinConsolidationSnapshot(
    service: DashSdkService,
    persistedInstantLocks: suspend (Collection<String>) -> Set<String> = { emptySet() }
): CoinJoinConsolidationSnapshot? {
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
    if (coins.any { it.isLocked || it.spendingTxid != null }) return null
    val unsettled = coins.filter { !it.isConfirmed && !it.isInstantLocked }
    if (unsettled.isNotEmpty()) {
        val txids = unsettled.map { coin -> coin.txid?.let { Utils.HEX.encode(it.reversedArray()) } ?: return null }
        val locked = persistedInstantLocks(txids.toSet())
        if (txids.any { it !in locked }) return null
    }
    val confirmed = parseCoinJoinConfirmedDuffs(manager.accountBalances(walletBytes)) ?: return null
    if (coins.sumOf { it.amount } != confirmed) return null
    return CoinJoinConsolidationSnapshot(walletId, coins, unusedCoinJoinAddress(addresses, account.id))
}

/**
 * Rejects (throws) a signed consolidation before it is persisted or broadcast.
 * A [CoinJoinConsolidationSnapshot.drain] must spend EXACTLY the snapshot:
 * `SelectionStrategy.ALL` skips UTXOs still reserved by an earlier build
 * (until the engine TTL), and a settled partial drain would strand the rest —
 * TRANSFERRING only completes on an empty account.
 */
internal fun validateCoinJoinConsolidation(
    raw: ByteArray, snapshot: CoinJoinConsolidationSnapshot, destination: String, params: NetworkParameters,
    minimumInputs: Int = 2
): String {
    val tx = Transaction(params, raw)
    require(tx.inputs.size in minimumInputs..COIN_JOIN_CONSOLIDATION_INPUT_LIMIT) { "Unexpected consolidation input count" }
    val owned = snapshot.coins.map { Utils.HEX.encode(it.outpoint) }.toSet()
    val spent = tx.inputs.map { Utils.HEX.encode(it.outpoint.bitcoinSerialize()) }
    require(spent.all { it in owned }) { "Unexpected consolidation input" }
    require(!snapshot.drain || (spent.size == owned.size && spent.toSet() == owned)) {
        "Drain must spend every snapshot coin"
    }
    val script = org.bitcoinj.script.ScriptBuilder.createOutputScript(Address.fromBase58(params, destination)).program
    require(tx.outputs.isNotEmpty() && tx.outputs.size <= 2 && tx.outputs.all { it.scriptBytes.contentEquals(script) }) {
        "Consolidation must pay only the selected CoinJoin address"
    }
    return tx.txId.toString()
}
