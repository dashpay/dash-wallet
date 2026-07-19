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
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.transactions.SdkTxInfoBuilder
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * POST-CUTOVER WALLET-DATA SEAM: serves the transaction reads of the
 * neutral [org.dash.wallet.common.WalletDataProvider] facade from the
 * Kotlin SDK's L1 store once the cutover is committed.
 *
 * Post-cutover the dashj wallet is HELD — it still holds keys and receives
 * self-authored sends, but externally-received transactions never reach it,
 * so every seam consumer that waits for a response tx (most critically the
 * CrowdNode signup/deposit state machine, which observes CrowdNode's
 * API-response transactions) freezes. This service closes that gap the same
 * way [CutoverUiDataService] does for the home screen: from the SDK's own
 * Room store, without mirroring anything into the dashj wallet.
 *
 * It maintains:
 * - [sdkTxInfos]: the current wallet-relevant transaction set as neutral
 *   [TxInfo] snapshots (null while inactive or not yet primed — callers
 *   fall back to the dashj path, which is stale-but-real, never fabricated);
 * - a hot event stream mirroring dashj's
 *   [de.schildbach.wallet.transactions.WalletObserver] semantics: a NEW
 *   wallet-relevant tx emits a coins-received/sent-style event; a lock/
 *   confirmation change on a known tx emits a confidence-style event
 *   (delivered only to `withConfidence` observers). The first snapshot
 *   after activation only PRIMES the baseline — no history replay, exactly
 *   like attaching dashj wallet listeners.
 *
 * ## Pre-cutover: provably inert
 *
 * The gate is the same persisted-state predicate as
 * [CutoverUiDataService.cutoverUiActive] (fails closed to dashj). Until a
 * deliberate cutover commit, [activeState] stays false and [sdkTxInfos]
 * stays null, so [de.schildbach.wallet.data.WalletDataAdapter] routes every
 * read down the unchanged dashj path. A rollback (CUT_OVER → DUAL_RUNNING)
 * cancels the pipeline and nulls the snapshot on the next DataStore
 * emission, reverting all seam reads to dashj.
 */
@Singleton
class CutoverTxSeamService internal constructor(
    private val source: CutoverUiSource,
    private val dashPayConfig: DashPayConfig,
    private val scope: CoroutineScope,
    private val networkParameters: NetworkParameters,
    private val dashjTxLookup: (Sha256Hash) -> Transaction?,
    private val walletBindRetryMs: Long = CutoverUiDataService.WALLET_BIND_RETRY_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        scope: CoroutineScope,
        walletData: WalletData
    ) : this(
        source = DashSdkCutoverUiSource(sdkService),
        dashPayConfig = dashPayConfig,
        scope = scope,
        networkParameters = Constants.NETWORK_PARAMETERS,
        dashjTxLookup = { hash -> walletData.getTransaction(hash) }
    )

    /** One seam event: a tx plus whether it is a confidence-style update. */
    internal class TxSeamEvent(val tx: TxInfo, val isConfidenceUpdate: Boolean)

    private val started = AtomicBoolean(false)

    private val _active = MutableStateFlow(false)

    /**
     * Reactive routing gate for [de.schildbach.wallet.data.WalletDataAdapter]:
     * false until a committed cutover has been observed AND the first SDK
     * snapshot has primed (initial value is synchronously false, so
     * pre-cutover subscriptions attach the dashj path immediately). Flipping
     * only after priming means consumers keep the dashj flow — valid, merely
     * stale-but-real data — during the bind/prime window instead of switching
     * to an event stream whose baseline hasn't loaded; flips back to false on
     * rollback or pipeline failure.
     */
    val activeState: StateFlow<Boolean> = _active.asStateFlow()

    // A plain volatile field, deliberately NOT a StateFlow: TxInfo equality
    // is txId-only, so two snapshot maps with different lock states compare
    // equal and StateFlow's equality conflation would silently drop updates.
    @Volatile
    private var _sdkTxInfos: Map<String, TxInfo>? = null

    /**
     * The SDK-fed wallet transaction set keyed by display txid hex, or null
     * while the seam feed is inactive (pre-cutover, rolled back, pipeline
     * failed) or not yet primed. Null = caller must use the dashj path.
     */
    fun sdkTxInfosOrNull(): Map<String, TxInfo>? = _sdkTxInfos

    // Hot, like dashj wallet listeners: events are only seen by collectors
    // subscribed when they fire. Buffered so a burst of store changes never
    // suspends the pipeline.
    private val events = MutableSharedFlow<TxSeamEvent>(extraBufferCapacity = 256)

    /**
     * Priming-window replay (bounded, per activation): rows of the priming
     * snapshot whose firstSeen timestamp is at/after the activation instant —
     * transactions that LANDED while the pipeline was still binding/priming,
     * which no consumer could have seen (they were on the dashj flow, and the
     * held dashj wallet never learns of SDK-side receives). Delivered to each
     * subscriber via [onSubscription] rather than a hot tryEmit at prime time,
     * because [activeState] consumers resubscribe asynchronously after the
     * flip — a hot emit fired before they reattach would be lost, which is the
     * exact event-loss bug this replay fixes. Old (pre-activation) rows are
     * never replayed, preserving dashj listener-attach parity; the list is
     * cleared on the next snapshot after priming (by then every flip-driven
     * resubscription has long attached, and later status changes emit normal
     * confidence events anyway) and on deactivation.
     */
    @Volatile
    private var primingReplay: List<TxInfo> = emptyList()

    /**
     * SDK-fed twin of `WalletObserver.observeTransactions`: new
     * wallet-relevant txs always emit; lock/confirmation changes emit only
     * when [withConfidence]. The neutral [filters] apply directly over the
     * SDK-fed [TxInfo] (any-match, like the dashj observer). Subscribers
     * additionally receive the bounded [primingReplay] (see there) as
     * new-tx-style events.
     */
    fun observeSdkTransactions(withConfidence: Boolean, filters: Array<out TransactionFilter>): Flow<TxInfo> =
        events
            .onSubscription {
                primingReplay.forEach { emit(TxSeamEvent(it, isConfidenceUpdate = false)) }
            }
            .filter { event -> withConfidence || !event.isConfidenceUpdate }
            .map { it.tx }
            .filter { tx -> filters.isEmpty() || filters.any { it.matches(tx) } }

    /**
     * [observeSdkTransactions] (confidence events included) with the CURRENT
     * snapshot state of every known tx replayed to the subscriber first.
     * Race-free by construction: [onSubscription] runs only after the event
     * subscription is live, so a lock/context change landing between a
     * caller's snapshot check and its subscription can never be missed.
     * Exists for the seam's `waitUntilLocked` ONLY — general observation must
     * use [observeSdkTransactions], whose no-history-replay semantics mirror
     * attaching dashj wallet listeners.
     */
    fun observeSdkTransactionsWithCurrentState(filters: Array<out TransactionFilter>): Flow<TxInfo> =
        events
            .onSubscription {
                _sdkTxInfos?.values?.forEach { emit(TxSeamEvent(it, isConfidenceUpdate = true)) }
            }
            .map { it.tx }
            .filter { tx -> filters.isEmpty() || filters.any { it.matches(tx) } }

    /** Same committed-cutover predicate as [CutoverUiDataService.cutoverUiActive]; fails closed (dashj). */
    private fun cutoverActive(): Flow<Boolean> =
        dashPayConfig.observe(DashPayConfig.CUTOVER_STATE)
            .map { stored -> !dashjEngineMayStart(CutoverState.fromStored(stored)) }
            .catch { e ->
                log.warn("failed to read the cutover state; seam reads stay on dashj", e)
                emit(false)
            }

    /**
     * Idempotent once-per-process start, called alongside
     * [CutoverUiDataService.start]. Contained: a pipeline failure logs,
     * nulls the snapshot and leaves every seam read on the dashj path.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            cutoverActive()
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) {
                        _active.value = false
                        _sdkTxInfos = null
                        primingReplay = emptyList()
                        return@collectLatest
                    }
                    // NOTE: activeState does NOT flip here — it flips inside
                    // runPipeline once the first snapshot has primed, so
                    // consumers stay on the (valid, stale-but-real) dashj
                    // flow through the bind/prime window instead of a seam
                    // flow with no baseline. This repeats on every
                    // rollback→recommit flap.
                    log.info("cutover committed — priming the SDK seam tx feed")
                    try {
                        runPipeline(activatedAtMs = clockMs())
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        log.error("cutover tx seam pipeline failed; seam reads fall back to dashj", t)
                        _active.value = false
                        _sdkTxInfos = null
                        primingReplay = emptyList()
                    }
                }
        }
    }

    /** The SDK bind (SdkWalletBinder) can lag app start — poll until bound. */
    private suspend fun awaitBoundWallet(): String {
        while (true) {
            val id = try {
                source.boundWalletIdOrNull()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK wallet lookup failed; retrying in {}ms", walletBindRetryMs, t)
                null
            }
            if (id != null) return id
            delay(walletBindRetryMs)
        }
    }

    private suspend fun runPipeline(activatedAtMs: Long) {
        val walletIdHex = awaitBoundWallet()
        var baselineStatuses: Map<String, L1TxUiStatus>? = null
        source.observeSeamTxSnapshots(walletIdHex)
            .catch { e ->
                log.error("SDK seam tx flow failed; seam reads fall back to dashj", e)
                _active.value = false
                _sdkTxInfos = null
                primingReplay = emptyList()
            }
            .collect { snapshot ->
                val infos = try {
                    SdkTxInfoBuilder.buildTxInfos(snapshot, networkParameters, dashjTxLookup)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.error("building seam TxInfos failed; keeping the previous snapshot", t)
                    return@collect
                }
                val statuses = snapshot.walletRecords.associate { it.txidHex to it.status }
                val previous = baselineStatuses
                _sdkTxInfos = infos
                baselineStatuses = statuses

                if (previous == null) {
                    // Priming snapshot: baseline only — no history replay for
                    // pre-activation rows (matches attaching dashj wallet
                    // listeners). Rows that LANDED during the priming window
                    // (firstSeen at/after activation) become the bounded
                    // per-subscriber replay, since no consumer flow could
                    // have carried them. Only then does the routing gate
                    // flip, so switching consumers find the replay in place.
                    primingReplay = snapshot.walletRecords
                        .filter { it.timestampMs >= activatedAtMs }
                        .mapNotNull { infos[it.txidHex] }
                    log.info(
                        "seam tx feed primed with {} wallet transactions ({} in the priming window); serving seam tx reads from the SDK",
                        infos.size,
                        primingReplay.size
                    )
                    _active.value = true
                    return@collect
                }
                // First post-prime snapshot: every flip-driven resubscription
                // has attached; retire the priming replay (see its docs).
                primingReplay = emptyList()
                for (record in snapshot.walletRecords) {
                    val tx = infos[record.txidHex] ?: continue
                    val emitted = when {
                        record.txidHex !in previous -> events.tryEmit(TxSeamEvent(tx, isConfidenceUpdate = false))
                        previous[record.txidHex] != record.status ->
                            events.tryEmit(TxSeamEvent(tx, isConfidenceUpdate = true))
                        else -> true
                    }
                    if (!emitted) {
                        log.warn("seam event buffer overflow — dropped event for {}", record.txidHex)
                    }
                }
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CutoverTxSeamService::class.java)
    }
}
