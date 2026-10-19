package de.schildbach.wallet.service.platform.sdk

import kotlinx.coroutines.flow.first
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.Utils
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.CoreAddressEntity
import org.dashfoundation.dashsdk.persistence.entities.TxoEntity
import org.json.JSONObject

internal const val COIN_JOIN_ASSET_LOCK_INPUT_LIMIT = 100
internal const val COIN_JOIN_CONSOLIDATION_INPUT_LIMIT = 500

/** A partial batch pays ≤ 2 outputs (target + change), so 3 inputs always shrink the account. */
internal const val COIN_JOIN_BATCH_MIN_INPUTS = 3

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

    /**
     * The next partial batch's output amount `T`, or 0 when no batch can shrink the account.
     *
     * Relies on `LARGEST_FIRST` (key-wallet `accumulate_coins_with_size`): it stops at the
     * first descending prefix `S_j ≥ T + fee(j)`. `T = max(S_m − allowance(m))` over m ≤ 500,
     * so it stops by m inputs; requiring `T` to exceed the largest `MIN − 1` coins means it
     * never stops sooner — no dominant coin fills a batch alone, even if the engine skips
     * reserved coins. Each batch thus spends ≥ 3 coins for ≤ 2 outputs, so the migration ends.
     */
    fun batchAmount(): Long {
        if (drain) return 1L
        val amounts = coins.map { it.amount }.sortedDescending().take(COIN_JOIN_CONSOLIDATION_INPUT_LIMIT)
        // Fee allowance: 160 bytes per P2PKH input + 100 bytes, at 2 duffs/byte (rechecked before broadcast).
        val best = amounts.runningReduce(Long::plus)
            .mapIndexed { index, sum -> sum - ((index + 1) * 160L + 100L) * 2L }.max()
        return if (best > amounts.take(COIN_JOIN_BATCH_MIN_INPUTS - 1).sum()) best else 0L
    }
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
 * TRANSFERRING completes only once every input it recorded is spent by it
 * ([transferSettled]).
 */
internal fun validateCoinJoinConsolidation(
    raw: ByteArray, snapshot: CoinJoinConsolidationSnapshot, destination: String, params: NetworkParameters,
    minimumInputs: Int = 2
): String {
    val tx = Transaction(params, raw)
    val floor = if (snapshot.drain) minimumInputs else maxOf(minimumInputs, COIN_JOIN_BATCH_MIN_INPUTS)
    require(tx.inputs.size in floor..COIN_JOIN_CONSOLIDATION_INPUT_LIMIT) { "Unexpected consolidation input count" }
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

/** `AssetLockFundingType::AssetLockShieldedAddressTopUp` discriminant. */
internal const val ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP = 5

/** Which of a wallet's own accounts holds a consolidation destination. */
enum class OwnDestinationAccount { BIP44, COIN_JOIN }

/**
 * The account of wallet [walletIdHex] owning [address] in the SDK's PERSISTED
 * address table — BIP44 account 0 (either chain) or CoinJoin account 0 — else
 * null. Binds a destination derived elsewhere to the wallet the build selects
 * by ID. Read failures propagate: callers fail closed.
 */
internal suspend fun ownDestinationAccount(db: DashDatabase, walletIdHex: String, address: String): OwnDestinationAccount? {
    val row = db.coreAddressDao().getByAddress(address) ?: return null
    if (row.poolTypeTag !in 0..1) return null
    val account = db.accountDao().getById(row.accountId ?: return null) ?: return null
    if (!account.walletId.contentEquals(Utils.HEX.decode(walletIdHex.lowercase())) || account.accountIndex != 0) {
        return null
    }
    return when {
        account.accountType == ACCOUNT_TYPE_TAG_STANDARD && account.standardTag == STANDARD_ACCOUNT_TAG_BIP44 ->
            OwnDestinationAccount.BIP44
        account.accountType == ACCOUNT_TYPE_TAG_COIN_JOIN -> OwnDestinationAccount.COIN_JOIN
        else -> null
    }
}

/** Persisted form of a shield's recorded inputs: comma-separated hex outpoints. */
internal fun encodeOutpoints(outpoints: List<ByteArray>): String = outpoints.joinToString(",") { Utils.HEX.encode(it) }

/** Inverse of [encodeOutpoints]; throws on a malformed record (callers fail closed). */
internal fun decodeOutpoints(raw: String): List<ByteArray> = raw.split(',').map { hex ->
    Utils.HEX.decode(hex.trim().lowercase()).also { require(it.size == 36) { "malformed outpoint" } }
}

/** The ONE transaction [rows] record as spending every outpoint of [inputs], else null. */
internal fun soleSpender(inputs: List<ByteArray>, rows: List<TxoEntity>): ByteArray? {
    if (inputs.isEmpty()) return null
    val byOutpoint = rows.associateBy { Utils.HEX.encode(it.outpoint) }
    var spender: ByteArray? = null
    for (input in inputs) {
        val row = byOutpoint[Utils.HEX.encode(input)] ?: return null
        val link = row.spendingTxid ?: return null
        if (!row.isSpent) return null
        if (spender == null) spender = link else if (!spender.contentEquals(link)) return null
    }
    return spender
}

/**
 * Has [tx] settled in the SDK's persisted TXOs: every input spent BY it, and
 * every output paying [destination] persisted as wallet [walletIdHex]'s TXO
 * ([outputRow] answers by outpoint)? Other CoinJoin coins are irrelevant.
 */
internal suspend fun transferSettled(
    tx: Transaction, inputRows: List<TxoEntity>, destination: String, walletIdHex: String, params: NetworkParameters,
    outputRow: suspend (ByteArray) -> TxoEntity?
): Boolean {
    val own = tx.txId.reversedBytes
    val spender = soleSpender(tx.inputs.map { it.outpoint.bitcoinSerialize() }, inputRows) ?: return false
    if (!spender.contentEquals(own)) return false
    val script = org.bitcoinj.script.ScriptBuilder.createOutputScript(Address.fromBase58(params, destination)).program
    val paid = tx.outputs.filter { it.scriptBytes.contentEquals(script) }
    if (paid.isEmpty()) return false
    val wallet = Utils.HEX.decode(walletIdHex.lowercase())
    return paid.all { output ->
        val row = outputRow(TransactionOutPoint(params, output.index.toLong(), tx.txId).bitcoinSerialize())
        row != null && row.address == destination && row.amount == output.value.value &&
            row.walletId.contentEquals(wallet) && row.txid?.contentEquals(own) == true
    }
}
