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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure stability gate for the auto-commit trigger: arms only after
 * [requiredReadings] CONSECUTIVE caught-up readings, and disarms (streak →
 * 0) the instant a NOT-caught-up reading arrives. This is what turns "the
 * scan momentarily touched the tip" into "the scan has held at the tip" —
 * one lucky reading is never enough, and any mid-scan/engine-down dip
 * resets the run. Host-JVM unit-testable (no Android, no coroutines).
 */
class CaughtUpStabilityGate(private val requiredReadings: Int) {
    private var consecutive = 0

    /** @return true iff the gate is armed AFTER applying this reading. */
    fun onReading(caughtUp: Boolean): Boolean {
        consecutive = if (caughtUp) consecutive + 1 else 0
        return consecutive >= requiredReadings
    }

    /** Current consecutive-caught-up count (for tests/diagnostics). */
    val streak: Int get() = consecutive

    fun reset() {
        consecutive = 0
    }
}

/**
 * Phase 5d AUTO-COMMIT: the startup-wired observer that drives an UPGRADE
 * install from dual-running to SDK-primary with NO debug broadcast, once
 * the SDK is genuinely ready — the automatic equivalent of a QA tester
 * running CHECK_CUTOVER then COMMIT_CUTOVER by hand.
 *
 * ## What it watches, and why THIS signal
 *
 * The trigger is [L1ShadowSyncService.progress]'s
 * [ShadowSyncProgress.scanCaughtUpToTip] — the SDK's wallet-relevant
 * compact-filter scan sitting at the chain tip — sustained across a short
 * [CaughtUpStabilityGate] window. It deliberately does NOT gate on the
 * SDK's own SYNCED flag, which never latches for a live shadow perpetually
 * chasing the moving tip (see [ShadowSyncProgress.scanCaughtUpToTip]); a
 * SYNCED-gated trigger would never fire on a real post-cutover wallet.
 *
 * ## Fail-safe by construction
 *
 * The stability gate is only the CADENCE pre-filter — the real safety gate
 * is [CutoverCoordinator.autoAdvanceToCutover], which re-runs the full
 * [evaluateCutoverReadiness] policy under its lock ([CutoverPolicy.MIN_PARITY_STREAK]
 * consecutive caught-up parity-MATCH probes, evidence freshness, no unconfirmed
 * self-authored txs, no in-flight identity op, shielded ready, a wallet
 * backup on disk). If ANY blocker holds, the commit does not happen and
 * the install stays dual-running on dashj — there is no timeout and no
 * forced commit, so a genuinely stuck scan can never flip the wallet.
 *
 * ## Lifecycle
 *
 * Started once per process from [PlatformSynchronizationService] after the
 * SDK bind pass, alongside the other post-cutover SDK services. Provably
 * inert while [ShadowSyncProgress.scanCaughtUpToTip] never holds (flag off
 * → the shadow never runs → progress stays IDLE → the gate never arms).
 * Once the flip lands it cancels its collector (nothing left to do). A
 * wallet wipe calls [rearmForNewWallet] so the freshly restored/created
 * wallet re-runs the trigger from scratch.
 */
@Singleton
class CutoverAutoCommitObserver internal constructor(
    private val l1ShadowSyncService: L1ShadowSyncService,
    private val coordinator: CutoverCoordinator,
    private val scope: CoroutineScope,
    private val requiredReadings: Int = AUTO_COMMIT_STABILITY_READINGS,
    private val attemptThrottleMs: Long = AUTO_COMMIT_ATTEMPT_THROTTLE_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    // Dagger sees only the injectable dependencies; the tuning params keep
    // their production defaults (tests use the primary constructor).
    @Inject
    constructor(
        l1ShadowSyncService: L1ShadowSyncService,
        coordinator: CutoverCoordinator,
        scope: CoroutineScope
    ) : this(l1ShadowSyncService, coordinator, scope, AUTO_COMMIT_STABILITY_READINGS)

    private val gate = CaughtUpStabilityGate(requiredReadings)
    private val lifecycleMutex = Mutex()
    private var collectJob: Job? = null

    @Volatile
    private var lastAttemptMs = Long.MIN_VALUE

    /** Throttle for [reportStillWaiting]. */
    @Volatile
    private var lastStatusLogMs = Long.MIN_VALUE

    /** Latched once the flip lands: no further attempts until [rearmForNewWallet]. */
    @Volatile
    private var committed = false

    /**
     * Start observing the shadow progress feed (idempotent — a second call
     * while already running is a no-op). Fire-and-forget: returns as soon
     * as the collector is launched.
     */
    fun start() {
        scope.launch {
            lifecycleMutex.withLock {
                if (collectJob?.isActive == true) return@withLock
                resetTriggerState()
                launchCollector()
                log.info(
                    "cutover auto-commit observer started (readings={}, throttleMs={})",
                    requiredReadings, attemptThrottleMs
                )
            }
        }
    }

    /**
     * Re-arm for a freshly wiped→restored/created wallet: cancel the old
     * collector, clear the in-memory streak/throttle/committed latch, and
     * restart. Called from the wallet-wipe path so the next wallet re-earns
     * its own cutover from scratch rather than inheriting this observer's
     * "already committed" state. Suspends until the state reset is applied
     * (the collector itself restarts asynchronously).
     */
    suspend fun rearmForNewWallet() {
        lifecycleMutex.withLock {
            collectJob?.cancel()
            collectJob = null
            resetTriggerState()
            launchCollector()
        }
        log.info("cutover auto-commit observer re-armed for a new wallet")
    }

    /** Clear the in-memory trigger latches. Caller holds [lifecycleMutex]. */
    private fun resetTriggerState() {
        committed = false
        gate.reset()
        lastAttemptMs = Long.MIN_VALUE
        lastStatusLogMs = Long.MIN_VALUE
    }

    /** Launch the progress collector. Caller holds [lifecycleMutex]. */
    private fun launchCollector() {
        collectJob = scope.launch {
            try {
                l1ShadowSyncService.progress.collect { progress ->
                    onReading(progress.scanCaughtUpToTip)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("cutover auto-commit observer collector failed", t)
            }
        }
    }

    /**
     * Apply one progress reading. Internal so tests drive the gate +
     * throttle + commit-latch logic directly without the flow. Returns the
     * cutover state after any attempt this reading made, or null when no
     * attempt was made (not armed / throttled / already committed).
     */
    internal suspend fun onReading(caughtUp: Boolean): CutoverState? {
        val armed = gate.onReading(caughtUp)
        if (!armed || committed) {
            reportStillWaiting(if (committed) null else "streak ${gate.streak}/$requiredReadings")
            return null
        }
        val now = nowMs()
        // Throttle attempts to the parity cadence: the gate arms on the ~1 Hz
        // progress feed, but readiness only changes when a new parity probe
        // lands, so hammering the (DB-reading) evaluator every tick is waste.
        if (lastAttemptMs != Long.MIN_VALUE && now - lastAttemptMs < attemptThrottleMs) return null
        lastAttemptMs = now
        return try {
            val status = coordinator.autoAdvanceToCutover()
            if (status.state != CutoverState.CUT_OVER) {
                // MO-995: `autoAdvanceToCutover` returns the readiness advisory
                // unchanged when a blocker holds and logs nothing, so a
                // non-committing observer used to be completely invisible.
                reportStillWaiting("readiness says ${status.state}")
            }
            if (status.state == CutoverState.CUT_OVER) {
                committed = true
                collectJob?.cancel()
                collectJob = null
                log.info("cutover auto-commit: SDK is now L1-primary (dashj held); observer standing down")
            }
            status.state
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("cutover auto-commit attempt failed; will retry on a later reading", t)
            null
        }
    }

    /**
     * Throttled "why am I not committing yet" line.
     *
     * MO-995 (Andrei, comment 91138 #2): in the 2026-09-02 field log this
     * observer logged `cutover auto-commit observer started` at 07:32:10 and
     * then NOTHING for the remaining 8.5 hours of the process, while the L1
     * shadow sat at `phase=SYNCED 100.0%` and parity reported `first sustained
     * MATCH streak complete`. Both of its non-committing paths returned early
     * in silence, so the log cannot say whether the stability gate never armed
     * or the readiness policy kept refusing — and the cutover (with it the
     * one-time upgrade explainer) fell through to the next process start ten
     * hours later. One line every [STATUS_LOG_INTERVAL_MS] closes that gap
     * without flooding the ~1 Hz feed.
     *
     * @param reason what is holding, or null when nothing is (already
     *   committed — there is nothing to report).
     */
    private fun reportStillWaiting(reason: String?) {
        if (reason == null) return
        val now = nowMs()
        if (lastStatusLogMs != Long.MIN_VALUE && now - lastStatusLogMs < STATUS_LOG_INTERVAL_MS) return
        lastStatusLogMs = now
        log.info("cutover auto-commit still waiting: {}", reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CutoverAutoCommitObserver::class.java)

        /**
         * Consecutive caught-up progress readings that arm the trigger. The
         * ~1 Hz progress feed makes this a few-second debounce — a light
         * pre-filter, NOT the safety gate. The load-bearing anti-blip gate
         * is [CutoverPolicy.MIN_PARITY_STREAK] consecutive caught-up parity
         * MATCH probes, re-checked inside
         * [CutoverCoordinator.autoAdvanceToCutover].
         */
        const val AUTO_COMMIT_STABILITY_READINGS = 5

        /**
         * Minimum spacing between commit attempts. Aligned to the parity
         * probe cadence ([L1ShadowSyncService.PARITY_INTERVAL_MS]): readiness
         * only advances when a fresh probe lands, so attempting more often
         * just re-reads the evaluator's evidence for nothing.
         */
        const val AUTO_COMMIT_ATTEMPT_THROTTLE_MS = L1ShadowSyncService.PARITY_INTERVAL_MS

        /**
         * Spacing between [reportStillWaiting] lines. Five minutes: rare
         * enough to be free on a ~1 Hz feed, frequent enough that a
         * multi-hour non-commit is unmistakable in a field log.
         */
        const val STATUS_LOG_INTERVAL_MS = 5 * 60 * 1000L
    }
}
