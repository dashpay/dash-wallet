/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.payments.logSendTxEvent
import de.schildbach.wallet.service.platform.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.Wallet
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of one [SdkBridgedTransactionFactory.bridge] attempt.
 * NEVER an exception: a bridge failure must degrade to the caller's
 * txid-only behavior (today's Phase 5b status quo), not fail a send whose
 * coins are already on the network.
 */
sealed class BridgedTxResult {
    /** The tx now lives in the dashj wallet; [transaction] is the LIVE instance. */
    data class Bridged(
        /**
         * The dashj wallet's own [Transaction] object — the instance in
         * its confidence table, so confidence listeners / IS-lock
         * animations / `isTransactionPending` all observe it.
         */
        val transaction: Transaction,
        /**
         * True when the wallet ALREADY held the tx (dashj's bloom filters
         * picked it up off the network before the bridge ran) and that
         * pre-existing instance was adopted instead of ours.
         */
        val adoptedWalletInstance: Boolean
    ) : BridgedTxResult()

    /**
     * The bridge could not commit (bytes never appeared, undecodable,
     * txid mismatch, wallet unavailable, commit failure — see [reason]).
     * The SDK broadcast itself is unaffected; the caller keeps the plain
     * txid it already has.
     */
    data class NotBridged(val reason: String, val cause: Throwable? = null) : BridgedTxResult()
}

/**
 * Phase 5c.2 of the dashj → Kotlin SDK migration
 * (`docs/kotlin-sdk-migration-plan.md`, "Phase 5c breakdown"): the BRIDGE.
 * After the Kotlin SDK builds/signs/broadcasts an L1 send, this factory
 * turns the broadcast's raw tx bytes back into a dashj [Transaction] and
 * commits it into the dashj wallet-of-record via `maybeCommitTx` — the
 * key 5c unlock: the returned instance is the wallet's live
 * confidence-table object, so waitToMatchFilters / LockedTransaction /
 * memo+exchangeRate persistence / result-screen IS animations keep
 * working with zero per-call-site changes.
 *
 * Engine-agnostic over the bytes source: callers pass the raw bytes
 * directly when they have them (the future GAP-1 `sendToAddressesEx`
 * return), else the factory resolves them from the SDK's Room
 * `transactions` row with a bounded poll (the [SdkTxRowSource] seam and
 * intervals shared with [L1SendProbeService] — 5c.1 measured that
 * latency).
 *
 * ## What one bridge pass does
 *
 * 1. Resolve the consensus bytes (given, or bounded Room poll).
 * 2. Reconstruct `Transaction(params, bytes)` and VERIFY the
 *    reconstructed txid equals the txid the SDK broadcast returned.
 * 3. Stamp what dashj's own `completeTx` would have stamped: confidence
 *    source SELF, purpose USER_PAYMENT, and the app-side memo /
 *    exchange rate when provided.
 * 4. `wallet.maybeCommitTx` — the same double-commit-safe call the BIP70
 *    path uses: if dashj's bloom filters already delivered the tx off
 *    the network, ADOPT the wallet's instance (carrying the memo /
 *    exchange rate / purpose onto it) instead of committing ours.
 * 5. Run the dashj send tail's equivalents, each contained:
 *    [WalletApplication.broadcastTransaction] (re-announce via dashj's
 *    peers — idempotent, and it arms the service-side broadcast
 *    machinery exactly like a dashj send),
 *    [L1ShadowSyncService.noteSelfSpendBroadcast] (the parity decider's
 *    self-spend grace), and the shared
 *    [de.schildbach.wallet.payments.logSendTxEvent] analytics.
 *
 * ## Failure semantics
 *
 * [bridge] NEVER throws into a send path (short of cancellation): every
 * failure — bytes timeout, decode error, txid mismatch, wallet missing,
 * commit throw — returns [BridgedTxResult.NotBridged], logged. The send
 * already happened on the SDK's peers; the worst case is today's
 * txid-only behavior where dashj learns of the spend via its own bloom
 * delivery.
 *
 * ## Wiring (Phase 5c.2)
 *
 * Not yet routed into any user-facing flow — 5c.4/5c.5 cut callers over
 * after GAP-1. The ONE live consumer is [SdkL1SendService]'s broadcast
 * path, fire-and-forget in the application scope and gated on
 * `BuildConfig.DEBUG` (bridging mutates dashj wallet state; production
 * stays txid-only until 5c.4), so Phase 5b soak sends exercise the
 * bridge-commit for real. The 5c.1 probe runs concurrently on the same
 * sends — it only READS, so they cannot fight; its `preexistedInDashj`
 * now usually reports this factory's commit (see [bridgeProbeLine]).
 */
@Singleton
class SdkBridgedTransactionFactory internal constructor(
    private val source: SdkTxRowSource,
    private val scope: CoroutineScope,
    /** The dashj wallet-of-record; null (→ NotBridged) while unavailable. */
    private val wallet: () -> Wallet?,
    /** Lazy per call: [Constants] untouched until a bridge actually runs. */
    private val networkParameters: () -> NetworkParameters = { Constants.NETWORK_PARAMETERS },
    /** Tail hook: [WalletApplication.broadcastTransaction] on the live instance. */
    private val onCommitted: (Transaction) -> Unit = {},
    /** Tail hook: [L1ShadowSyncService.noteSelfSpendBroadcast]. */
    private val onSelfSpendBroadcast: () -> Unit = {},
    /** Tail hook: the shared [de.schildbach.wallet.payments.logSendTxEvent] analytics. */
    private val logSendTx: suspend (Transaction, Wallet) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Bytes-resolution poll cadence/bound — the probe's Room intervals. */
    private val pollIntervalMs: Long = L1SendProbeService.ROOM_POLL_INTERVAL_MS,
    private val bytesTimeoutMs: Long = L1SendProbeService.ROOM_POLL_TIMEOUT_MS
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        walletData: WalletData,
        walletApplication: WalletApplication,
        l1ShadowSyncService: L1ShadowSyncService,
        identityConfig: BlockchainIdentityConfig,
        identityRepository: IdentityRepository,
        analyticsService: AnalyticsService,
        scope: CoroutineScope
    ) : this(
        source = DashSdkTxRowSource(sdkService),
        scope = scope,
        wallet = { walletData.wallet },
        onCommitted = { walletApplication.broadcastTransaction(it) },
        onSelfSpendBroadcast = { l1ShadowSyncService.noteSelfSpendBroadcast() },
        logSendTx = { tx, wallet ->
            logSendTxEvent(tx, wallet, identityConfig, identityRepository, analyticsService)
        }
    )

    /**
     * Fire-and-forget [bridge] pass, detached in the application scope —
     * the shape the 5c.2 debug consumer uses. Contains every failure;
     * can never block or fail the send it follows.
     */
    fun bridgeInBackground(
        txidHex: String,
        rawTxBytes: ByteArray? = null,
        memo: String? = null,
        exchangeRate: ExchangeRate? = null
    ): Job = scope.launch {
        try {
            bridge(txidHex, rawTxBytes, memo, exchangeRate)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // bridge() already contains everything; this is belt-and-braces.
            log.warn("bridge pass failed unexpectedly for txid {}; send unaffected", txidHex, t)
        }
    }

    /**
     * One bridge pass (see class KDoc). Returns the wallet's live
     * instance on success; NEVER throws (short of cancellation).
     *
     * @param txidHex the display-order txid the SDK broadcast returned —
     *   the reconstruction is verified against it.
     * @param rawTxBytes the signed consensus bytes when the caller has
     *   them (GAP-1); null → bounded poll of the SDK Room row.
     * @param memo app-side memo to persist on the committed instance.
     * @param exchangeRate fiat rate to persist on the committed instance.
     */
    suspend fun bridge(
        txidHex: String,
        rawTxBytes: ByteArray? = null,
        memo: String? = null,
        exchangeRate: ExchangeRate? = null
    ): BridgedTxResult = try {
        bridgeContained(txidHex, rawTxBytes, memo, exchangeRate)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        notBridged(txidHex, "unexpected bridge failure", t)
    }

    private suspend fun bridgeContained(
        txidHex: String,
        rawTxBytes: ByteArray?,
        memo: String?,
        exchangeRate: ExchangeRate?
    ): BridgedTxResult {
        val bytes = rawTxBytes ?: resolveBytesFromSdkStore(txidHex)
            ?: return notBridged(
                txidHex, "tx bytes unavailable (SDK Room row absent after ${bytesTimeoutMs}ms)", null
            )
        val wallet = walletOrNull()
            ?: return notBridged(txidHex, "dashj wallet unavailable", null)
        org.bitcoinj.core.Context.propagate(wallet.context)

        // Reconstruct and verify: the bytes must BE the broadcast tx.
        val tx = try {
            Transaction(networkParameters(), bytes)
        } catch (e: Exception) {
            return notBridged(txidHex, "tx bytes failed to decode", e)
        }
        if (!tx.txId.toString().equals(txidHex, ignoreCase = true)) {
            return notBridged(txidHex, "reconstructed txid ${tx.txId} does not match the broadcast txid", null)
        }

        // Stamp what dashj's completeTx stamps on its own sends, BEFORE the
        // commit so it all persists with the wallet.
        tx.getConfidence(wallet.context).source = TransactionConfidence.Source.SELF
        tx.purpose = Transaction.Purpose.USER_PAYMENT
        memo?.let { tx.memo = it }
        exchangeRate?.let { tx.exchangeRate = it }

        // The BIP70-guarded commit: false = the wallet already had the tx
        // (bloom delivery won the race) — adopt ITS instance, ours would be
        // a dead object outside the confidence table.
        val committed = wallet.maybeCommitTx(tx)
        val live = wallet.getTransaction(tx.txId) ?: tx
        if (live !== tx) {
            live.purpose = Transaction.Purpose.USER_PAYMENT
            memo?.let { live.memo = it }
            exchangeRate?.let { live.exchangeRate = it }
        }
        log.info(
            "bridged SDK tx {} into the dashj wallet ({})",
            txidHex,
            if (committed) "committed" else "adopted the wallet's existing instance"
        )

        runDashjSendTail(live, wallet)
        return BridgedTxResult.Bridged(live, adoptedWalletInstance = !committed)
    }

    /**
     * The dashj send tail's equivalents on the live instance. Each hook is
     * individually contained: the bridge is already a success and a tail
     * failure must not undo it.
     */
    private suspend fun runDashjSendTail(live: Transaction, wallet: Wallet) {
        runCatching { onCommitted(live) }
            .onFailure { log.warn("bridged tx {}: broadcastTransaction hook failed", live.txId, it) }
        runCatching { onSelfSpendBroadcast() }
            .onFailure { log.warn("failed to record the self-spend marker", it) }
        try {
            logSendTx(live, wallet)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("bridged tx {}: send analytics failed", live.txId, t)
        }
    }

    /** Bounded poll of the SDK Room `transactions` row for the tx bytes. */
    private suspend fun resolveBytesFromSdkStore(txidHex: String): ByteArray? {
        val wireTxid = wireTxidBytesOrNull(txidHex)
        if (wireTxid == null) {
            log.warn("bridge: malformed txid {} — cannot resolve the SDK Room row", txidHex)
            return null
        }
        return pollForValueBounded(
            timeoutMs = bytesTimeoutMs,
            pollIntervalMs = pollIntervalMs,
            nowMs = nowMs,
            onReadFailure = { log.debug("bridge poll read failed: {}", it.toString()) }
        ) { source.sdkTxRow(wireTxid)?.rawTxBytes }
    }

    /** [wallet] with failures contained (a throw must not escape the bridge). */
    private fun walletOrNull(): Wallet? = try {
        wallet()
    } catch (e: Exception) {
        log.warn("bridge: dashj wallet lookup failed", e)
        null
    }

    private fun notBridged(txidHex: String, reason: String, cause: Throwable?): BridgedTxResult.NotBridged {
        log.warn("SDK tx {} not bridged into dashj ({}); txid-only fallback", txidHex, reason, cause)
        return BridgedTxResult.NotBridged(reason, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkBridgedTransactionFactory::class.java)
    }
}
