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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * DEBUG-BUILDS-ONLY adb triggers for the Phase 5d cutover, for a supervised
 * on-device rehearsal:
 *
 * ```
 * # ADVISORY — recompute readiness only (DUAL_RUNNING ⇄ READY_OBSERVED):
 * adb shell am broadcast -a hashengineering.darkcoin.wallet_test.action.CHECK_CUTOVER
 * # COMMIT — the atomic flip (READY_OBSERVED → CUT_OVER, only if still Ready).
 * # Takes effect for the dashj engine gate on the NEXT launch; force-stop + relaunch.
 * adb shell am broadcast -a hashengineering.darkcoin.wallet_test.action.COMMIT_CUTOVER
 * # ROLLBACK — undo the flip while still legal (CUT_OVER → DUAL_RUNNING).
 * adb shell am broadcast -a hashengineering.darkcoin.wallet_test.action.ROLLBACK_CUTOVER
 * ```
 *
 * CHECK runs [CutoverCoordinator.observeReadiness] (the ADVISORY edge; it can
 * never commit) and logs the state, verdict, and raw evidence. COMMIT and
 * ROLLBACK drive the real transitions ([CutoverCoordinator.commitCutover] /
 * [CutoverCoordinator.rollback]) — both re-check readiness under the
 * coordinator lock and are no-ops when illegal. Same registration contract
 * as [L1ShadowDebugReset]: dynamic, [BuildConfig.DEBUG]-gated, exported only
 * so `adb shell` can deliver it; nothing ships in release builds.
 *
 * The COMMIT/ROLLBACK triggers exist so a rehearsal can exercise the real
 * cutover: COMMIT flips the persisted state, the next launch reads
 * [CutoverCoordinator.dashjEngineMayStart] and holds the dashj L1 engine,
 * and ROLLBACK restores dual-running. They are the ONLY way to reach
 * CUT_OVER — there is no in-app UI and no automatic commit.
 */
object CutoverDebugReadout {
    /** Deliberately flavor-independent (a fixed string, not `applicationId`-derived). */
    const val ACTION_CHECK_CUTOVER = "hashengineering.darkcoin.wallet_test.action.CHECK_CUTOVER"
    const val ACTION_COMMIT_CUTOVER = "hashengineering.darkcoin.wallet_test.action.COMMIT_CUTOVER"
    const val ACTION_ROLLBACK_CUTOVER = "hashengineering.darkcoin.wallet_test.action.ROLLBACK_CUTOVER"

    private val log = LoggerFactory.getLogger(CutoverDebugReadout::class.java)

    /**
     * Register the debug readout receiver; a provable no-op unless
     * [BuildConfig.DEBUG].
     */
    @JvmStatic
    fun registerIfDebug(
        context: Context,
        coordinator: CutoverCoordinator,
        evidenceCollector: CutoverEvidenceCollector
    ) {
        if (!BuildConfig.DEBUG) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                log.info("cutover debug broadcast {} received", action)
                scope.launch {
                    when (action) {
                        ACTION_CHECK_CUTOVER -> {
                            logEvidence()
                            runCatching { coordinator.observeReadiness() }
                                .onSuccess { logStatus("readout", it) }
                                .onFailure { log.warn("cutover readiness pass failed", it) }
                        }
                        ACTION_COMMIT_CUTOVER -> {
                            // The real flip — re-checked under the coordinator lock, a
                            // no-op unless READY_OBSERVED and still Ready. Engine gate
                            // takes effect on the NEXT launch (force-stop + relaunch).
                            logEvidence()
                            runCatching { coordinator.commitCutover() }
                                .onSuccess {
                                    logStatus("COMMIT", it)
                                    if (it.state == CutoverState.CUT_OVER) {
                                        log.info(
                                            "cutover COMMIT applied — force-stop and relaunch: the dashj L1 " +
                                                "engine will be held (SDK owns L1). ROLLBACK_CUTOVER undoes this."
                                        )
                                    } else {
                                        log.warn(
                                            "cutover COMMIT was a no-op (state={}) — not READY_OBSERVED or " +
                                                "readiness lost under the lock; run CHECK_CUTOVER to see blockers.",
                                            it.state
                                        )
                                    }
                                }
                                .onFailure { log.warn("cutover commit failed", it) }
                        }
                        ACTION_ROLLBACK_CUTOVER -> {
                            runCatching { coordinator.rollback() }
                                .onSuccess {
                                    logStatus("ROLLBACK", it)
                                    log.info(
                                        "cutover ROLLBACK result state={} — force-stop and relaunch to " +
                                            "restore the dashj L1 engine.",
                                        it.state
                                    )
                                }
                                .onFailure { log.warn("cutover rollback failed", it) }
                        }
                        else -> log.warn("unknown cutover debug action {}", action)
                    }
                }
            }

            /**
             * Evidence logged separately from the coordinator pass so the
             * readout still shows the raw inputs when collection fails (the
             * coordinator would degrade that to a blocking verdict).
             */
            private suspend fun logEvidence() {
                runCatching { evidenceCollector.collect() }
                    .onSuccess { e ->
                        val tail = e.parityObservations.takeLastWhile { it.synced && it.match }
                        log.info(
                            "cutover evidence: parityObs={} (matching tail={} spanning {}s, newest {}s ago) " +
                                "unconfirmedSelfAuthored={} identityOpInFlight={} pendingShieldedLocks={} " +
                                "shieldedEnabled={} shieldedReady={} walletBackupExists={}",
                            e.parityObservations.size,
                            tail.size,
                            if (tail.size >= 2) (tail.last().atElapsedMillis - tail.first().atElapsedMillis) / 1000 else 0,
                            if (tail.isNotEmpty()) (e.nowElapsedMillis - tail.last().atElapsedMillis) / 1000 else -1,
                            e.unconfirmedSelfAuthoredTxs,
                            e.identityOperationInFlight,
                            e.pendingShieldedLocks,
                            e.shieldedEnabled,
                            e.shieldedReady,
                            e.walletBackupExists
                        )
                    }
                    .onFailure { log.warn("cutover evidence collection failed", it) }
            }

            private fun logStatus(tag: String, status: CutoverStatus) {
                log.info(
                    "cutover {}: state={} ready={} blockers={} dashjEngineMayStart={}",
                    tag,
                    status.state,
                    status.ready,
                    status.verdict.blockers,
                    dashjEngineMayStart(status.state)
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CHECK_CUTOVER)
            addAction(ACTION_COMMIT_CUTOVER)
            addAction(ACTION_ROLLBACK_CUTOVER)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        log.info(
            "cutover debug readout receiver registered (debug build only; actions={}, {}, {})",
            ACTION_CHECK_CUTOVER,
            ACTION_COMMIT_CUTOVER,
            ACTION_ROLLBACK_CUTOVER
        )
    }
}
