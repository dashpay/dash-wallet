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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure decoding / formatting (host-testable) ────────────────────────

/**
 * Output-level view of a single-recipient send transaction, for the fee/
 * change parity probe ([L1SendProbeService]).
 *
 * The RECIPIENT output is identified by an exact `{address, amount}` match
 * first — the correct labeling even for a SELF-SEND, where every output
 * pays the wallet and only the send target distinguishes payment from
 * change — falling back to an address-only match (fee-subtracted sends,
 * e.g. dashj's `emptyWallet`, change the recipient value). Every other
 * output is CHANGE.
 */
internal data class SendOutputsSummary(
    /**
     * Base58 of every non-recipient output, comma-joined in vout order;
     * null when the tx pays only the recipient (no change output).
     */
    val changeAddress: String?,
    /** Whether some output pays the recipient at all. */
    val recipientFound: Boolean,
    /** Per-output labels for the DETAIL block: `vout:address=duffs (recipient|change)`. */
    val outputLabels: List<String>
)

/**
 * Classify [tx]'s outputs against the send's `{recipient, amount}` — see
 * [SendOutputsSummary] for the labeling rules. Pure over a parsed dashj
 * [Transaction], so the change-address extraction is host-JVM testable
 * from fixture bytes. An output whose script yields no address (OP_RETURN,
 * nonstandard) is labeled `nonstandard` and counts as change.
 */
internal fun summarizeSendOutputs(
    tx: Transaction,
    params: NetworkParameters,
    recipientBase58: String,
    amountDuffs: Long
): SendOutputsSummary {
    data class Out(val vout: Int, val address: String?, val duffs: Long)

    val outs = tx.outputs.map { output ->
        val address = try {
            output.scriptPubKey.getToAddress(params, true).toBase58()
        } catch (e: Exception) {
            null
        }
        Out(output.index, address, output.value.value)
    }
    val recipientVout = outs.firstOrNull { it.address == recipientBase58 && it.duffs == amountDuffs }?.vout
        ?: outs.firstOrNull { it.address == recipientBase58 }?.vout
    val change = outs.filter { it.vout != recipientVout }
    return SendOutputsSummary(
        changeAddress = change.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.address ?: "nonstandard" },
        recipientFound = recipientVout != null,
        outputLabels = outs.map { out ->
            val role = if (out.vout == recipientVout) "recipient" else "change"
            "${out.vout}:${out.address ?: "nonstandard"}=${out.duffs} ($role)"
        }
    )
}

/**
 * The one-line `L1FeeParity` summary (Phase 5c.0). Field semantics:
 * - `route=sdk`: `sdkFee` is the SDK Room row's fee; `rowLatencyMs` is
 *   broadcast → Room `transactions` row visible (the GAP-6 empirical
 *   answer), `timeout` when the row never landed within the poll bound.
 * - `route=dashj`: the fee field is named `dashjFee` (the committed tx's
 *   actual fee — the sanity baseline) and `rowLatencyMs` is `n/a`.
 * - `dashjEstimatedFee`: the dashj dry-run estimate for the same
 *   `{address, amount, emptyWallet}`; `n/a` when the estimate failed.
 * - `delta` = fee − dashjEstimatedFee, `n/a` unless both are known.
 * - `changeAddress`: from the decoded tx bytes; `none` = decoded, no
 *   change output; `n/a` = row missing or bytes undecodable.
 */
internal fun feeParityLine(
    route: String,
    txidHex: String,
    actualFeeDuffs: Long?,
    dashjEstimatedFeeDuffs: Long?,
    rowLatencyMs: Long?,
    changeAddress: String
): String {
    val feeField = if (route == L1SendProbeService.ROUTE_SDK) "sdkFee" else "dashjFee"
    val delta = if (actualFeeDuffs != null && dashjEstimatedFeeDuffs != null) {
        (actualFeeDuffs - dashjEstimatedFeeDuffs).toString()
    } else {
        "n/a"
    }
    val rowLatency = when {
        route != L1SendProbeService.ROUTE_SDK -> "n/a"
        rowLatencyMs != null -> rowLatencyMs.toString()
        else -> "timeout"
    }
    return "L1FeeParity route=$route txid=$txidHex" +
        " $feeField=${actualFeeDuffs ?: "n/a"}" +
        " dashjEstimatedFee=${dashjEstimatedFeeDuffs ?: "n/a"}" +
        " delta=$delta rowLatencyMs=$rowLatency changeAddress=$changeAddress"
}

/**
 * The multi-line `L1FeeParity DETAIL` evidence block, emitted only when
 * `delta != 0` (both fees known and different) — the fee-policy-divergence
 * evidence the Phase 5c risk note asks for (ECONOMIC_FEE 1000/kB vs the
 * undocumented Rust default).
 */
internal fun feeParityDetailLog(
    route: String,
    txidHex: String,
    addressBase58: String,
    amountDuffs: Long,
    emptyWallet: Boolean,
    actualFeeDuffs: Long,
    dashjEstimatedFeeDuffs: Long,
    txSizeBytes: Int?,
    economicFeePerKb: Long,
    outputLabels: List<String>
): String = buildString {
    append("L1FeeParity DETAIL route=$route txid=$txidHex")
    append("\n  send: address=$addressBase58 amount=$amountDuffs duffs emptyWallet=$emptyWallet")
    append(
        "\n  fees: actual=$actualFeeDuffs dashjEstimated=$dashjEstimatedFeeDuffs " +
            "delta=${actualFeeDuffs - dashjEstimatedFeeDuffs} duffs"
    )
    val implied = if (txSizeBytes != null && txSizeBytes > 0) {
        (actualFeeDuffs * 1000 / txSizeBytes).toString()
    } else {
        "n/a"
    }
    append(
        "\n  tx: size=${txSizeBytes ?: "n/a"} bytes impliedFeePerKb=$implied " +
            "(dashj ECONOMIC_FEE=$economicFeePerKb/kB)"
    )
    append("\n  outputs: ${if (outputLabels.isEmpty()) "none" else outputLabels.joinToString(", ")}")
}

/**
 * The one-line `L1BridgeProbe` summary (Phase 5c.1) — the bridge
 * feasibility evidence for one SDK-routed send:
 * - `bytesOk`: the Room row landed AND its `transactionData` bytes parsed
 *   as a dashj [Transaction];
 * - `txidMatch`: the reconstructed tx's display txid equals the txid the
 *   SDK broadcast returned (`n/a` without decodable bytes);
 * - `roomLatencyMs`: broadcast → Room row (same number the `L1FeeParity`
 *   line carries; repeated so each tag is self-contained);
 * - `dashjNetworkLatencyMs`: broadcast → the dashj wallet holding the tx
 *   via its own bloom-filter/network delivery, `timeout` past the bound;
 * - `inputsAllOurs`: every reconstructed input spends an outpoint the
 *   dashj wallet knows and owns — the `maybeCommitTx` bridgeability
 *   precondition (`n/a` when undecodable or the wallet is unavailable);
 * - `preexistedInDashj`: the dashj wallet already held the tx at probe
 *   start. The probe itself never commits, so with the 5c.2 debug bridge
 *   consumer OFF this should be false; with it ON
 *   ([SdkBridgedTransactionFactory]'s commit races this probe) `true`
 *   here — and a near-zero `dashjNetworkLatencyMs` — usually means the
 *   bridged commit landed first, NOT network delivery.
 */
internal fun bridgeProbeLine(
    txidHex: String,
    bytesOk: Boolean,
    txidMatch: Boolean?,
    roomLatencyMs: Long?,
    dashjNetworkLatencyMs: Long?,
    inputsAllOurs: Boolean?,
    preexistedInDashj: Boolean
): String =
    "L1BridgeProbe txid=$txidHex" +
        " bytesOk=$bytesOk" +
        " txidMatch=${txidMatch ?: "n/a"}" +
        " roomLatencyMs=${roomLatencyMs ?: "timeout"}" +
        " dashjNetworkLatencyMs=${dashjNetworkLatencyMs ?: "timeout"}" +
        " inputsAllOurs=${inputsAllOurs ?: "n/a"}" +
        " preexistedInDashj=$preexistedInDashj"

/**
 * Display-order (byte-reversed) txid hex → the raw little-endian wire
 * bytes the SDK's Room `transactions.txid` column stores; null when the
 * string is not 32-byte hex.
 */
internal fun wireTxidBytesOrNull(displayTxidHex: String): ByteArray? = try {
    Sha256Hash.wrap(displayTxidHex).reversedBytes
} catch (e: Exception) {
    null
}

// ── Source seam ───────────────────────────────────────────────────────

/** One SDK Room `transactions` row, reduced to what the probes/bridge read. */
internal data class SdkTxRow(
    /** The row's `fee` column (duffs), null until/unless the SDK populates it. */
    val feeDuffs: Long?,
    /** The row's consensus-encoded `transactionData` bytes. */
    val rawTxBytes: ByteArray
)

/**
 * Seam over the SDK Room `transactions`-row read, shared by the 5c.0/5c.1
 * probes ([L1SendProbeService]) and the 5c.2 bridge factory
 * ([SdkBridgedTransactionFactory]) so both are host-JVM unit-testable.
 * Read-only by contract: no implementation may mutate the SDK store.
 */
internal interface SdkTxRowSource {
    /**
     * The SDK Room `transactions` row for the WIRE-order txid, or null
     * while absent (callers poll this — GAP-6's broadcast-time
     * persistence latency) or when the database is unavailable.
     */
    suspend fun sdkTxRow(txidWireBytes: ByteArray): SdkTxRow?
}

/**
 * Seam over the probe's two read surfaces — the SDK's Room database and
 * the dashj wallet — so the polling/formatting orchestration in
 * [L1SendProbeService] is host-JVM unit-testable. Read-only by contract:
 * no implementation may commit, broadcast, or mutate either stack.
 */
internal interface L1SendProbeSource : SdkTxRowSource {
    /** Whether the dashj wallet currently holds the tx (network/bloom delivery). */
    suspend fun dashjHasTx(txidHex: String): Boolean

    /**
     * True/false when the dashj wallet can judge EVERY input of [tx]:
     * each spent outpoint resolves to a known wallet tx whose output
     * `isMine`. Null when the dashj wallet is unavailable or the check
     * itself failed.
     */
    suspend fun inputsAllOurs(tx: Transaction): Boolean?
}

/** Production [SdkTxRowSource]: the live SDK Room DB. */
internal class DashSdkTxRowSource(
    private val service: DashSdkService
) : SdkTxRowSource {

    override suspend fun sdkTxRow(txidWireBytes: ByteArray): SdkTxRow? {
        // Deliberately NOT ensureStarted(): an SDK-routed send definitionally
        // ran with the SDK up, and a best-effort read after one must never
        // boot it.
        val db = service.databaseOrNull() ?: return null
        val row = db.transactionDao().getByTxid(txidWireBytes) ?: return null
        return SdkTxRow(feeDuffs = row.fee, rawTxBytes = row.transactionData)
    }
}

/** Production [L1SendProbeSource]: the live SDK Room DB + dashj wallet. */
internal class DashSdkL1SendProbeSource(
    service: DashSdkService,
    private val walletData: WalletDataProvider
) : L1SendProbeSource {

    private val rowSource = DashSdkTxRowSource(service)

    override suspend fun sdkTxRow(txidWireBytes: ByteArray): SdkTxRow? =
        rowSource.sdkTxRow(txidWireBytes)

    override suspend fun dashjHasTx(txidHex: String): Boolean =
        walletData.wallet?.getTransaction(Sha256Hash.wrap(txidHex)) != null

    override suspend fun inputsAllOurs(tx: Transaction): Boolean? {
        val wallet = walletData.wallet ?: return null
        return try {
            tx.inputs.all { input ->
                val parent = wallet.getTransaction(input.outpoint.hash)
                val output = parent?.outputs?.getOrNull(input.outpoint.index.toInt())
                output != null && output.isMine(wallet)
            }
        } catch (e: Exception) {
            log.debug("inputsAllOurs check failed: {}", e.toString())
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DashSdkL1SendProbeSource::class.java)
    }
}

// ── The probe service ─────────────────────────────────────────────────

/**
 * Phase 5c.0 + 5c.1 of the dashj → Kotlin SDK migration
 * (`docs/kotlin-sdk-migration-plan.md`, "Phase 5c breakdown"): DEBUG-ONLY,
 * fire-and-forget instrumentation that runs after a send and measures the
 * evidence the 5c.2 bridge cutover needs. Observation only — nothing here
 * can affect, delay, or fail a send: every entry point launches into the
 * application scope, contains all failures, and the hook site
 * ([de.schildbach.wallet.payments.SendCoinsTaskRunner]'s neutral
 * `sendCoins` overload) is compile-time gated on `BuildConfig.DEBUG`,
 * mirroring the shadow harness's debug-only rule. SDK-routed probes run
 * only after a real SDK broadcast, which itself requires the
 * `USE_KOTLIN_SDK_L1_SHADOW` parity evidence — the same debug context.
 *
 * ## 5c.0 — fee & change parity (`L1FeeParity`)
 *
 * After an SDK-routed broadcast: compute the dashj dry-run estimate for
 * the same `{address, amount, emptyWallet}` (the caller passes the
 * existing `estimateNetworkFee` path in as a lambda — no dependency
 * cycle), poll the SDK Room `transactions` table for the txid (bounded —
 * the row latency is GAP-6's empirical answer), decode the row's
 * `transactionData` with dashj and extract the change address
 * ([summarizeSendOutputs]), then log ONE [feeParityLine] plus a
 * [feeParityDetailLog] block when the fees disagree (the fee-policy-
 * divergence evidence: dashj's ECONOMIC_FEE 1000/kB vs the SDK's
 * undocumented Rust default). The same comparison logs for dashj-routed
 * sends through the neutral overload (`route=dashj`, actual fee vs its
 * own dry-run estimate — the sanity baseline that also validates this
 * instrument). CAVEAT on both routes: the dry-run runs concurrently with/
 * after the real send against a live wallet, so its coin selection can
 * see the send's own spend (pending change) — an occasional nonzero
 * baseline delta means "the estimate raced the send", not a fee bug;
 * PERSISTENT sdk-route deltas are the real signal.
 *
 * ## 5c.1 — bridge feasibility (`L1BridgeProbe`)
 *
 * Once the Room row lands: reconstruct the dashj [Transaction] from the
 * raw bytes and verify the txid round-trips; measure how long until the
 * dashj wallet ALSO sees the tx via its own network delivery (bounded);
 * and check every reconstructed input is wallet-relevant — the exact
 * preconditions of the 5c.2 `maybeCommitTx` bridge. This probe NEVER
 * calls `maybeCommitTx`: the live dashj wallet already receives the tx
 * via its bloom filters, and committing here would double-handle it. It
 * instead logs what WOULD happen ([bridgeProbeLine]): whether the tx
 * pre-existed in dashj at probe start and whether the inputs are all
 * ours (`maybeCommitTx` would accept it). NOTE since 5c.2: on DEBUG
 * builds [SdkBridgedTransactionFactory] DOES bridge-commit the same tx
 * concurrently with this probe (probe reads, factory commits — they
 * cannot fight), so `preexistedInDashj=true` and a near-instant
 * `dashjNetworkLatencyMs` now usually measure the bridged commit rather
 * than network delivery — see [bridgeProbeLine].
 */
@Singleton
class L1SendProbeService internal constructor(
    private val source: L1SendProbeSource,
    private val scope: CoroutineScope,
    /** Lazy per call: [Constants] untouched until a debug probe actually runs. */
    private val networkParameters: () -> NetworkParameters = { Constants.NETWORK_PARAMETERS },
    private val economicFeePerKb: () -> Long = {
        org.dash.wallet.common.util.Constants.ECONOMIC_FEE.value
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = ROOM_POLL_INTERVAL_MS,
    private val roomTimeoutMs: Long = ROOM_POLL_TIMEOUT_MS,
    private val dashjNetworkTimeoutMs: Long = DASHJ_NETWORK_TIMEOUT_MS
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        walletData: WalletDataProvider,
        scope: CoroutineScope
    ) : this(
        source = DashSdkL1SendProbeSource(sdkService, walletData),
        scope = scope
    )

    /**
     * Fire-and-forget 5c.0 + 5c.1 probe pass for an SDK-routed broadcast.
     * Never blocks or fails the send: the whole pass runs detached in the
     * application scope and contains every failure.
     *
     * @param dashjDryRunFeeDuffs the caller's dashj dry-run — the existing
     *   `estimateNetworkFee` path for the same send, returning the fee in
     *   duffs or null; a throw is caught and logged here.
     */
    fun probeSdkSendInBackground(
        txidHex: String,
        addressBase58: String,
        amountDuffs: Long,
        emptyWallet: Boolean,
        dashjDryRunFeeDuffs: suspend () -> Long?
    ): Job = scope.launch {
        try {
            probeSdkSend(txidHex, addressBase58, amountDuffs, emptyWallet, dashjDryRunFeeDuffs)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("L1FeeParity/L1BridgeProbe pass failed for txid {}; send unaffected", txidHex, t)
        }
    }

    /**
     * One probe pass (see class KDoc). Internal so tests drive it directly
     * with short poll bounds; production enters via
     * [probeSdkSendInBackground], which contains every failure.
     */
    internal suspend fun probeSdkSend(
        txidHex: String,
        addressBase58: String,
        amountDuffs: Long,
        emptyWallet: Boolean,
        dashjDryRunFeeDuffs: suspend () -> Long?
    ) {
        val startMs = nowMs()
        // 5c.1 pre-check: whether the dashj wallet already holds the tx.
        // The probe never commits, so true here means either network
        // delivery beat the probe or (DEBUG, 5c.2) the bridge factory's
        // concurrent commit landed first.
        val preexistedInDashj = try {
            source.dashjHasTx(txidHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
        coroutineScope {
            // Broadcast → dashj-sees-it latency, measured from probe start
            // (≈ broadcast time), concurrent with the Room poll below.
            val dashjLatencyDeferred = async {
                pollForCondition(dashjNetworkTimeoutMs) { source.dashjHasTx(txidHex) }
            }
            // The dashj dry-run for the same send. Runs now — as close to
            // the broadcast as an async probe can get — to minimize the
            // window in which dashj's coin selection already sees the
            // SDK tx's spend (see the class-KDoc caveat).
            val estimateDeferred = async {
                try {
                    dashjDryRunFeeDuffs()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("L1FeeParity dashj dry-run estimate failed for txid {}", txidHex, t)
                    null
                }
            }

            // GAP-6 measurement: broadcast → SDK Room row.
            val wireTxid = wireTxidBytesOrNull(txidHex)
            if (wireTxid == null) {
                log.warn("L1FeeParity probe: malformed txid {} — skipping the Room poll", txidHex)
            }
            val row = wireTxid?.let { pollForValue(roomTimeoutMs) { source.sdkTxRow(it) } }
            val rowLatencyMs = if (row != null) nowMs() - startMs else null

            var decoded: Transaction? = null
            var summary: SendOutputsSummary? = null
            if (row != null) {
                decoded = try {
                    Transaction(networkParameters(), row.rawTxBytes)
                } catch (e: Exception) {
                    log.warn("L1FeeParity probe: failed to decode the Room row's tx bytes for {}", txidHex, e)
                    null
                }
                summary = decoded?.let {
                    summarizeSendOutputs(it, networkParameters(), addressBase58, amountDuffs)
                }
            }
            val txidMatch = decoded?.txId?.toString()?.equals(txidHex, ignoreCase = true)

            // The 5c.0 summarizing line (+ DETAIL when the fees disagree).
            val estimatedFee = estimateDeferred.await()
            val sdkFee = row?.feeDuffs
            log.info(
                feeParityLine(
                    route = ROUTE_SDK,
                    txidHex = txidHex,
                    actualFeeDuffs = sdkFee,
                    dashjEstimatedFeeDuffs = estimatedFee,
                    rowLatencyMs = rowLatencyMs,
                    changeAddress = summary?.let { it.changeAddress ?: "none" } ?: "n/a"
                )
            )
            if (sdkFee != null && estimatedFee != null && sdkFee != estimatedFee) {
                log.warn(
                    feeParityDetailLog(
                        route = ROUTE_SDK,
                        txidHex = txidHex,
                        addressBase58 = addressBase58,
                        amountDuffs = amountDuffs,
                        emptyWallet = emptyWallet,
                        actualFeeDuffs = sdkFee,
                        dashjEstimatedFeeDuffs = estimatedFee,
                        txSizeBytes = decoded?.let { tryTxSize(it) },
                        economicFeePerKb = economicFeePerKb(),
                        outputLabels = summary?.outputLabels ?: emptyList()
                    )
                )
            }

            // The 5c.1 bridge feasibility line. NO maybeCommitTx here —
            // observation only (see class KDoc).
            val dashjNetworkLatencyMs = dashjLatencyDeferred.await()
            val inputsAllOurs = decoded?.let {
                try {
                    source.inputsAllOurs(it)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    null
                }
            }
            log.info(
                bridgeProbeLine(
                    txidHex = txidHex,
                    bytesOk = decoded != null,
                    txidMatch = txidMatch,
                    roomLatencyMs = rowLatencyMs,
                    dashjNetworkLatencyMs = dashjNetworkLatencyMs,
                    inputsAllOurs = inputsAllOurs,
                    preexistedInDashj = preexistedInDashj
                )
            )
        }
    }

    /**
     * Start the dashj dry-run estimate for the BASELINE (dashj-routed)
     * comparison, detached in the application scope so the send is never
     * delayed. Called immediately BEFORE the dashj send so the estimate's
     * coin selection has the best chance of seeing the pre-send UTXO set
     * (it still races the send — see the class-KDoc caveat). The returned
     * deferred never completes exceptionally.
     */
    fun dryRunEstimateAsync(dashjDryRunFeeDuffs: suspend () -> Long?): Deferred<Long?> =
        scope.async {
            try {
                dashjDryRunFeeDuffs()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1FeeParity dashj dry-run estimate failed (dashj-route baseline)", t)
                null
            }
        }

    /**
     * Fire-and-forget 5c.0 baseline log for a dashj-routed send: the
     * committed [tx]'s actual fee and change outputs vs the
     * [dryRunEstimateAsync] estimate captured alongside the send. Cheap —
     * everything but the awaited estimate is already in memory. Same
     * `L1FeeParity` tag, `route=dashj`.
     */
    fun probeDashjSendInBackground(
        tx: Transaction,
        addressBase58: String,
        amountDuffs: Long,
        emptyWallet: Boolean,
        estimatedFeeDuffs: Deferred<Long?>
    ): Job = scope.launch {
        try {
            val txidHex = tx.txId.toString()
            val actualFee = tx.fee?.value
            val summary = try {
                summarizeSendOutputs(tx, networkParameters(), addressBase58, amountDuffs)
            } catch (e: Exception) {
                log.warn("L1FeeParity baseline: failed to summarize the dashj tx outputs for {}", txidHex, e)
                null
            }
            val estimatedFee = estimatedFeeDuffs.await()
            log.info(
                feeParityLine(
                    route = ROUTE_DASHJ,
                    txidHex = txidHex,
                    actualFeeDuffs = actualFee,
                    dashjEstimatedFeeDuffs = estimatedFee,
                    rowLatencyMs = null,
                    changeAddress = summary?.let { it.changeAddress ?: "none" } ?: "n/a"
                )
            )
            if (actualFee != null && estimatedFee != null && actualFee != estimatedFee) {
                log.warn(
                    feeParityDetailLog(
                        route = ROUTE_DASHJ,
                        txidHex = txidHex,
                        addressBase58 = addressBase58,
                        amountDuffs = amountDuffs,
                        emptyWallet = emptyWallet,
                        actualFeeDuffs = actualFee,
                        dashjEstimatedFeeDuffs = estimatedFee,
                        txSizeBytes = tryTxSize(tx),
                        economicFeePerKb = economicFeePerKb(),
                        outputLabels = summary?.outputLabels ?: emptyList()
                    )
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("L1FeeParity baseline pass failed; send unaffected", t)
        }
    }

    /** Serialized size for the DETAIL block, or null when serialization fails. */
    private fun tryTxSize(tx: Transaction): Int? = try {
        tx.bitcoinSerialize().size
    } catch (e: Exception) {
        null
    }

    /**
     * Poll [read] every [pollIntervalMs] until it yields a value or
     * [timeoutMs] elapses; a throwing read counts as "not there yet"
     * (logged at debug) so one bad poll never kills the pass.
     */
    private suspend fun <T : Any> pollForValue(timeoutMs: Long, read: suspend () -> T?): T? =
        pollForValueBounded(
            timeoutMs = timeoutMs,
            pollIntervalMs = pollIntervalMs,
            nowMs = nowMs,
            onReadFailure = { log.debug("L1SendProbe poll read failed: {}", it.toString()) },
            read = read
        )

    /** [pollForValue] for a boolean condition; returns the elapsed ms when it turned true. */
    private suspend fun pollForCondition(timeoutMs: Long, read: suspend () -> Boolean): Long? {
        val start = nowMs()
        pollForValue(timeoutMs) { if (read()) Unit else null } ?: return null
        return nowMs() - start
    }

    companion object {
        private val log = LoggerFactory.getLogger(L1SendProbeService::class.java)

        internal const val ROUTE_SDK = "sdk"
        internal const val ROUTE_DASHJ = "dashj"

        /** Room `transactions`-row poll cadence (GAP-6 latency resolution). */
        internal const val ROOM_POLL_INTERVAL_MS = 500L

        /** How long a broadcast tx gets to appear in the SDK's Room store. */
        internal const val ROOM_POLL_TIMEOUT_MS = 30_000L

        /**
         * How long the dashj wallet gets to see the SDK tx via its own
         * bloom-filter/network delivery (typically seconds when online).
         */
        internal const val DASHJ_NETWORK_TIMEOUT_MS = 60_000L
    }
}

/**
 * Poll [read] every [pollIntervalMs] until it yields a value or [timeoutMs]
 * elapses (null on timeout); a throwing read counts as "not there yet"
 * (reported via [onReadFailure]) so one bad poll never kills the pass.
 * Shared by the 5c.0/5c.1 probes and the 5c.2 bridge factory
 * ([SdkBridgedTransactionFactory]).
 */
internal suspend fun <T : Any> pollForValueBounded(
    timeoutMs: Long,
    pollIntervalMs: Long,
    nowMs: () -> Long,
    onReadFailure: (Throwable) -> Unit = {},
    read: suspend () -> T?
): T? {
    val start = nowMs()
    while (true) {
        val value = try {
            read()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onReadFailure(t)
            null
        }
        if (value != null) return value
        if (nowMs() - start + pollIntervalMs > timeoutMs) return null
        delay(pollIntervalMs)
    }
}
