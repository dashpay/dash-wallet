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
 * DEBUG-BUILDS-ONLY adb trigger for the Phase 5d cutover readiness readout:
 *
 * ```
 * adb shell am broadcast -a hashengineering.darkcoin.wallet_test.action.CHECK_CUTOVER
 * ```
 *
 * Runs [CutoverCoordinator.observeReadiness] once — the ADVISORY edge only
 * (DUAL_RUNNING ⇄ READY_OBSERVED; it can never commit, roll back, or
 * settle) — and logs the resulting state, verdict, and the raw evidence it
 * was judged on. Same registration contract as [L1ShadowDebugReset]:
 * dynamic, [BuildConfig.DEBUG]-gated, exported only so `adb shell` can
 * deliver it; nothing ships in release builds.
 */
object CutoverDebugReadout {
    /** Deliberately flavor-independent (a fixed string, not `applicationId`-derived). */
    const val ACTION_CHECK_CUTOVER = "hashengineering.darkcoin.wallet_test.action.CHECK_CUTOVER"

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
                log.info("debug broadcast {} received — running one advisory readiness pass", intent.action)
                scope.launch {
                    // Evidence logged separately from the coordinator pass so the
                    // readout still shows the raw inputs when collection fails
                    // (the coordinator would degrade that to a blocking verdict).
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
                    runCatching { coordinator.observeReadiness() }
                        .onSuccess { status ->
                            log.info(
                                "cutover readout: state={} ready={} blockers={} dashjEngineMayStart={}",
                                status.state,
                                status.ready,
                                status.verdict.blockers,
                                dashjEngineMayStart(status.state)
                            )
                        }
                        .onFailure { log.warn("cutover readiness pass failed", it) }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_CHECK_CUTOVER),
            ContextCompat.RECEIVER_EXPORTED
        )
        log.info(
            "cutover debug readout receiver registered (debug build only; action={})",
            ACTION_CHECK_CUTOVER
        )
    }
}
