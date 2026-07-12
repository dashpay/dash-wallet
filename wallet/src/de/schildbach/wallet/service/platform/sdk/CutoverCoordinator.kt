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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** What a cutover-advance attempt did, for the debug readout and logs. */
data class CutoverStatus(
    val state: CutoverState,
    val verdict: CutoverVerdict
) {
    val ready: Boolean get() = verdict.ready
}

/**
 * Phase 5d: the thin, persisted wrapper over the pure [nextCutoverState]
 * machine and the [CutoverEvidenceCollector] → [evaluateCutoverReadiness]
 * pipeline. Owns NO policy (the evaluator does) and NO transition rules
 * (the state machine does) — only the persistence, the single-flight
 * serialization, and the atomic config write that IS the flip.
 *
 * Everything here is reversible and inert by default: with no explicit
 * COMMIT the state never leaves DUAL_RUNNING/READY_OBSERVED, and
 * [dashjEngineMayStart] stays true — so wiring this up changes nothing
 * observable until a deliberate [commitCutover] (a debug action first).
 */
@Singleton
class CutoverCoordinator @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val evidenceCollector: CutoverEvidenceCollector
) {
    private val mutex = Mutex()

    suspend fun currentState(): CutoverState =
        CutoverState.fromStored(runCatching { dashPayConfig.get(DashPayConfig.CUTOVER_STATE) }.getOrNull())

    /** Whether the dashj L1 engine may start this launch (engine-start sites consult this). */
    suspend fun dashjEngineMayStart(): Boolean = dashjEngineMayStart(currentState())

    /**
     * Recompute readiness and apply the ADVISORY edge
     * (DUAL_RUNNING ⇄ READY_OBSERVED) — safe to call on every parity
     * probe. Never commits or rolls back. Returns the resulting status.
     */
    suspend fun observeReadiness(): CutoverStatus =
        transition(CutoverAction.OBSERVE_READINESS)

    /**
     * Commit the flip — the single atomic write making the SDK the L1
     * source of truth. Legal only from READY_OBSERVED with a still-Ready
     * verdict (the guard re-checks readiness under the lock, so a race
     * that lost readiness after observation cannot flip). No-op otherwise.
     */
    suspend fun commitCutover(): CutoverStatus =
        transition(CutoverAction.COMMIT_CUTOVER)

    /** Undo a flip while still legal (CUT_OVER → DUAL_RUNNING). */
    suspend fun rollback(): CutoverStatus =
        transition(CutoverAction.ROLLBACK)

    private suspend fun transition(action: CutoverAction): CutoverStatus = mutex.withLock {
        val current = currentState()
        val verdict = try {
            evaluateCutoverReadiness(evidenceCollector.collect())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Evidence unavailable = not provably ready. Block by verdict,
            // never advance; report the current state unchanged.
            log.warn("cutover readiness evaluation failed; treating as NOT ready", t)
            return@withLock CutoverStatus(current, CutoverVerdict(setOf(CutoverBlocker.PARITY_EVIDENCE_STALE)))
        }
        val next = nextCutoverState(current, action, verdict.ready)
        if (next != current) {
            runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_STATE, next.name) }
                .onFailure {
                    if (it is CancellationException) throw it
                    log.warn("cutover state persist failed ({} -> {}); keeping {}", current, next, current, it)
                    return@withLock CutoverStatus(current, verdict)
                }
            log.info("cutover state {} -> {} on {} (ready={})", current, next, action, verdict.ready)
        }
        CutoverStatus(next, verdict)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CutoverCoordinator::class.java)
    }
}
