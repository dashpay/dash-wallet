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

import de.schildbach.wallet.service.BlockchainStateDataProvider
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kill-list Step B (sync-state track): POST-CUTOVER BLOCKCHAIN-STATE FEED.
 *
 * Once the cutover is committed (persisted state CUT_OVER/SETTLED — the
 * same `!dashjEngineMayStart` predicate as [CutoverUiDataService] and
 * [SdkL1SendService]), the held dashj engine stops writing the app-wide
 * `blockchain_state` Room row, freezing every sync indicator that
 * observes [org.dash.wallet.common.services.BlockchainStateProvider]. This
 * service re-derives that row from the Kotlin SDK's SPV progress instead:
 *
 * - source: [L1ShadowSyncService.progress] (the mirrored SDK 1 Hz SPV
 *   feed) plus the SDK's `spvTipUnixSecondsFlow` tip timestamp;
 * - cadence: a 1 s tick (also the stall clock) — EQUALITY-GATED: a
 *   derivation is propagated ONLY when the polled [SdkChainSnapshot]
 *   actually changed, the iOS-validated pattern (naive 1 Hz propagation
 *   re-wrote identical state every second and burned CPU — a documented
 *   iOS bug this port must not repeat);
 * - sink: [BlockchainStateDataProvider.updateSdkBlockchainState], which
 *   serializes with the dashj writers on the provider's single-thread
 *   scope, preserves fields the SDK has no knowledge of (chainlock
 *   height), and composes the derived stall NETWORK impediment with the
 *   service-maintained connectivity impediments.
 *
 * ## Pre-cutover: provably inert
 *
 * The gate is false for every install until a deliberate cutover commit,
 * so no SDK flow is collected and no row is ever written — dashj's
 * BlockchainState pipeline stays byte-identical. A rollback
 * (CUT_OVER → DUAL_RUNNING) cancels the derivation via [collectLatest].
 */
@Singleton
class SdkBlockchainStateService internal constructor(
    private val dashPayConfig: DashPayConfig,
    private val scope: CoroutineScope,
    private val progress: Flow<ShadowSyncProgress>,
    private val tipUnixSeconds: Flow<Long>,
    private val applyUpdate: (SdkBlockchainStateUpdate) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val stallThresholdMs: Long = SPV_STALL_THRESHOLD_MS,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS
) {
    @Inject
    constructor(
        dashPayConfig: DashPayConfig,
        scope: CoroutineScope,
        l1ShadowSyncService: L1ShadowSyncService,
        sdkService: DashSdkService,
        blockchainStateDataProvider: BlockchainStateDataProvider
    ) : this(
        dashPayConfig = dashPayConfig,
        scope = scope,
        progress = l1ShadowSyncService.progress,
        // The SDK's SPV tip timestamp feed — collected lazily so the SDK
        // is only ever touched once the cutover gate is active.
        tipUnixSeconds = flow {
            sdkService.ensureStarted()
            val manager = checkNotNull(sdkService.walletManagerOrNull()) {
                "SDK wallet manager missing after ensureStarted()"
            }
            emitAll(manager.spvTipUnixSecondsFlow)
        },
        applyUpdate = blockchainStateDataProvider::updateSdkBlockchainState
    )

    private val started = AtomicBoolean(false)

    /** Same reactive cutover gate as [CutoverUiDataService.cutoverUiActive]; fails closed (dashj). */
    internal fun cutoverStateFeedActive(): Flow<Boolean> =
        dashPayConfig.observe(DashPayConfig.CUTOVER_STATE)
            .map { stored -> !dashjEngineMayStart(CutoverState.fromStored(stored)) }
            .catch { e ->
                log.warn("failed to read the cutover state; BlockchainState stays dashj-fed", e)
                emit(false)
            }

    /**
     * Idempotent once-per-process start (call site:
     * [de.schildbach.wallet.service.platform.PlatformSynchronizationService]'s
     * SDK-engine kick, alongside [CutoverUiDataService.start]). A
     * derivation failure logs and leaves the last-written row in place —
     * it never throws out of the collecting scope.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            cutoverStateFeedActive()
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    log.info("cutover committed — deriving BlockchainState from the SDK SPV feed")
                    try {
                        runDerivation()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        log.error("SDK BlockchainState derivation failed; row left as-is", t)
                    }
                }
        }
    }

    private suspend fun runDerivation() {
        // Equality gate state: the last PROPAGATED snapshot, plus the last
        // OBSERVED progress and when it changed (the stall clock's input).
        var lastPropagated: SdkChainSnapshot? = null
        var lastProgress: ShadowSyncProgress? = null
        var lastProgressChangeMs = nowMs()

        val safeTip = tipUnixSeconds
            .onStart { emit(0L) } // unblock combine() even if the SDK feed lags
            .catch { e ->
                log.warn("SPV tip timestamp feed failed; falling back to the estimator", e)
                emit(0L)
            }

        combine(progress, safeTip, ticker()) { p, tip, _ -> p to tip }
            .collect { (p, tip) ->
                val now = nowMs()
                if (p != lastProgress) {
                    lastProgress = p
                    lastProgressChangeMs = now
                }
                val snapshot = SdkChainSnapshot(
                    progress = p,
                    tipUnixSeconds = tip,
                    stalled = isSpvProgressStalled(p.phase, now - lastProgressChangeMs, stallThresholdMs)
                )
                // THE equality gate: identical snapshot → no derivation, no
                // Room write, no flow churn (see class KDoc).
                if (snapshot == lastPropagated) return@collect
                lastPropagated = snapshot
                applyUpdate(deriveBlockchainStateUpdate(snapshot, now))
            }
    }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(tickIntervalMs)
        }
    }

    companion object {
        /** The poll/stall-check cadence — the SDK's own progress feed is 1 Hz. */
        internal const val TICK_INTERVAL_MS = 1_000L
        private val log = LoggerFactory.getLogger(SdkBlockchainStateService::class.java)
    }
}
