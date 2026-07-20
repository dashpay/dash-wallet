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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
    private val evidenceCollector: CutoverEvidenceCollector,
    // The default keeps host tests (which construct with two args) working;
    // Dagger injects the application scope in production.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val mutex = Mutex()

    suspend fun currentState(): CutoverState =
        CutoverState.fromStored(runCatching { dashPayConfig.get(DashPayConfig.CUTOVER_STATE) }.getOrNull())

    /**
     * Whether the dashj L1 engine may start this launch (engine-start sites
     * consult this). True in every non-committed state, AND — the hardening
     * guard — also true when the state IS committed (CUT_OVER/SETTLED) but the
     * SDK L1 engine is disabled ([DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] off).
     *
     * WHY: the stored cutover state is per-install-persisted while the shadow
     * flag can be toggled off on a LATER launch. Without this guard a committed
     * install whose flag was turned off would hold dashj (state committed) AND
     * never start the SDK shadow (start gated on the flag) — leaving the wallet
     * with NO L1 engine at all. The immediate/auto commits both self-gate on the
     * flag so this cannot arise same-launch, but the flag is external state; this
     * makes the engine-start decision robust to a later toggle-off. Never hold
     * dashj unless the SDK will actually own L1.
     */
    suspend fun dashjEngineMayStart(): Boolean {
        val state = currentState()
        if (dashjEngineMayStart(state)) return true
        if (!sdkL1EngineEnabled()) {
            log.warn(
                "cutover state is {} but USE_KOTLIN_SDK_L1_SHADOW is off — the SDK L1 engine will " +
                    "not start, so allowing dashj to run (never hold dashj without an SDK L1 owner)",
                state
            )
            return true
        }
        return false
    }

    /**
     * REACTIVE mirror of the suspend [dashjEngineMayStart] ownership decision:
     * emits whether the Dash Kotlin SDK owns L1 right now, and RE-EMITS when
     * the cutover commits (or the SDK L1 flag flips) mid-launch — so UI that
     * observes it (the About screen's L1-engine row) updates live when the
     * new SDK-primary auto-commit takes over ~15-30s after launch without a
     * relaunch, or immediately after a restore.
     *
     * SDK owns L1 exactly when the state is committed (CUT_OVER/SETTLED, i.e.
     * the pure [dashjEngineMayStart] gate says dashj must NOT start) AND the
     * SDK L1 engine is actually enabled ([DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW])
     * — the identical condition the suspend [dashjEngineMayStart] fail-safe
     * encodes, kept in lockstep by reusing the same pure predicate rather than
     * duplicating the gate. Observes the CUTOVER_STATE and shadow-flag
     * DataStore keys, so any persisted transition flows through.
     */
    fun sdkOwnsL1Flow(): Flow<Boolean> =
        combine(
            dashPayConfig.observe(DashPayConfig.CUTOVER_STATE),
            dashPayConfig.observe(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW)
        ) { storedState, shadowEnabled ->
            val state = CutoverState.fromStored(storedState)
            !dashjEngineMayStart(state) && shadowEnabled == true
        }.distinctUntilChanged()

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

    /**
     * Drive the full advisory→commit path in one call — the AUTOMATIC
     * cutover trigger ([CutoverAutoCommitObserver]) does exactly what a
     * manual CHECK_CUTOVER + COMMIT_CUTOVER would: recompute the advisory
     * readiness edge (DUAL_RUNNING → READY_OBSERVED if Ready), then commit
     * (READY_OBSERVED → CUT_OVER if STILL Ready under the lock). Both legs
     * re-check readiness, so this is fail-safe by construction: if any
     * blocker holds, the state never leaves DUAL_RUNNING/READY_OBSERVED and
     * [dashjEngineMayStart] stays true — no timeout, no forced commit.
     * Idempotent: a no-op once already CUT_OVER/SETTLED.
     */
    suspend fun autoAdvanceToCutover(): CutoverStatus {
        val advisory = observeReadiness()
        // Only READY_OBSERVED can legally commit; anything else (still
        // DUAL_RUNNING because a blocker holds, or already past the flip)
        // is returned unchanged without attempting the commit leg.
        if (advisory.state != CutoverState.READY_OBSERVED) return advisory
        return commitCutover()
    }

    /**
     * Restore/new-wallet path: make the SDK the L1 source of truth
     * IMMEDIATELY at wallet-setup time, bypassing the dual-run readiness
     * gate. A freshly created or restored wallet has NO already-synced
     * dashj balance to protect, so there is nothing to be "ready" about —
     * letting dashj run its full (multi-day for large CoinJoin wallets)
     * block sync first would only add a pointless wait. Committing here
     * holds dashj from the start and lets the SDK do the fast initial sync
     * (a post-restore "syncing" wait is the expected, correct UX).
     *
     * Self-gated on [DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW]: holding dashj
     * while the SDK L1 engine is OFF (release/prod, or the flag toggled
     * off) would leave the wallet with NO L1 engine at all — so when the
     * SDK path is inactive this is a deliberate no-op and the wallet stays
     * on dashj. Only advances a pre-commit state; never clobbers
     * CUT_OVER/SETTLED. Never throws.
     */
    /**
     * Fire-and-forget [commitForFreshWalletSetup] for the Java `setWallet`
     * seam. `setWallet` is called on the MAIN thread by the restore-from-FILE
     * path (`RestoreWalletFromFileViewModel.restoreWallet`), so it must NEVER
     * block on DataStore I/O (first-access init can take hundreds of ms). The
     * home screen reads the cutover state reactively, so a brief
     * dual→committed transition on a fresh restore is harmless, and the
     * engine-start gate ([dashjEngineMayStart]) fails safe (dashj runs) if the
     * commit has not landed by the time the blockchain service starts.
     */
    fun commitForFreshWalletSetupAsync() {
        scope.launch { commitForFreshWalletSetup() }
    }

    suspend fun commitForFreshWalletSetup(): CutoverStatus = mutex.withLock {
        val current = currentState()
        if (current == CutoverState.CUT_OVER || current == CutoverState.SETTLED) {
            return@withLock CutoverStatus(current, READY_VERDICT)
        }
        if (!sdkL1EngineEnabled()) {
            log.info(
                "fresh-wallet cutover skipped: USE_KOTLIN_SDK_L1_SHADOW is off — the SDK L1 " +
                    "engine is inactive, so dashj must keep owning L1 (staying {})",
                current
            )
            return@withLock CutoverStatus(current, READY_VERDICT)
        }
        writeState(current, CutoverState.CUT_OVER, "fresh-wallet setup (restore/new)")
    }

    /**
     * Per-wallet reset: on a wallet WIPE, put the cutover state back to
     * DUAL_RUNNING so a newly restored/created wallet re-runs the flow from
     * scratch (immediate-commit for a fresh restore, or dual-run →
     * caught-up → auto-commit for whatever comes next) instead of
     * inheriting the WIPED wallet's committed state. Critical for
     * correctness: without this, a Reset-then-restore would start already
     * CUT_OVER and hold dashj while the SDK has not yet synced the new
     * wallet. Unconditional write (independent of the SDK flag — a stored
     * CUT_OVER must be cleared even if the flag is momentarily off). Never
     * throws.
     */
    suspend fun resetForWalletWipe(): CutoverStatus = mutex.withLock {
        val current = currentState()
        if (current == CutoverState.DUAL_RUNNING) {
            return@withLock CutoverStatus(current, READY_VERDICT)
        }
        writeState(current, CutoverState.DUAL_RUNNING, "wallet wipe")
    }

    /** Whether the SDK L1 engine (shadow) is enabled — the fresh-commit gate. */
    private suspend fun sdkL1EngineEnabled(): Boolean =
        runCatching { dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) == true }
            .getOrDefault(false)

    /**
     * The atomic config write shared by the direct (non-readiness) state
     * moves ([commitForFreshWalletSetup]/[resetForWalletWipe]). A failed
     * persist keeps the old state (reported unchanged), never a phantom
     * flip. Must be called under [mutex].
     */
    private suspend fun writeState(from: CutoverState, to: CutoverState, reason: String): CutoverStatus {
        if (from == to) return CutoverStatus(from, READY_VERDICT)
        return runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_STATE, to.name) }
            .fold(
                onSuccess = {
                    log.info("cutover state {} -> {} ({})", from, to, reason)
                    CutoverStatus(to, READY_VERDICT)
                },
                onFailure = {
                    if (it is CancellationException) throw it
                    log.warn("cutover state persist failed ({} -> {}, {}); keeping {}", from, to, reason, from, it)
                    CutoverStatus(from, READY_VERDICT)
                }
            )
    }

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

        /**
         * The verdict reported by the DIRECT (non-readiness) state moves
         * ([commitForFreshWalletSetup]/[resetForWalletWipe]): those bypass
         * the evaluator by design, so there are no blockers to report; the
         * meaningful result is the resulting [CutoverStatus.state].
         */
        private val READY_VERDICT = CutoverVerdict(emptySet())
    }
}
