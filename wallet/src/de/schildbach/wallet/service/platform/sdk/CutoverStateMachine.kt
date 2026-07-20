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

/**
 * Phase 5d cutover state machine (docs/kotlin-sdk-migration-plan.md). The
 * PURE transition logic — the coordinator is a thin persistence wrapper
 * around [nextCutoverState]. Modelling the whole legal graph here (rather
 * than scattering `if`s across the coordinator) keeps the cutover policy
 * reviewable and host-testable, including the load-bearing invariants:
 * you can only CUT_OVER from a Ready-observed state, and rollback is legal
 * until (not after) SETTLED.
 */
enum class CutoverState {
    /** Both engines live; SDK is instrumentation only. Today's behavior. */
    DUAL_RUNNING,

    /**
     * The readiness evaluator has reported Ready — the install is a
     * candidate for cutover but has NOT flipped yet. Purely advisory; the
     * user/coordinator still triggers the flip. Reverts to DUAL_RUNNING if
     * readiness is later lost (e.g. a new unconfirmed tx).
     */
    READY_OBSERVED,

    /**
     * The single atomic flip happened: SDK is the L1 source of truth, the
     * dashj engine is not started on the next launch, the `.wallet` file is
     * retained read-only. Rollback to DUAL_RUNNING is still legal here.
     */
    CUT_OVER,

    /**
     * The migration horizon passed (N releases). dashj artifacts are
     * removable for this install; rollback is no longer offered.
     */
    SETTLED;

    companion object {
        /** Parse a persisted value, defaulting to [DUAL_RUNNING] on absent/garbage. */
        fun fromStored(value: String?): CutoverState =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: DUAL_RUNNING
    }
}

/** The transitions a caller can request; the guard decides if they're legal now. */
enum class CutoverAction {
    /** Recompute the advisory readiness edge (DUAL_RUNNING ⇄ READY_OBSERVED). */
    OBSERVE_READINESS,

    /** The user/coordinator commits the flip (only from READY_OBSERVED, only if Ready). */
    COMMIT_CUTOVER,

    /** Undo a flip while still legal (CUT_OVER → DUAL_RUNNING). */
    ROLLBACK,

    /** Cross the migration horizon (CUT_OVER → SETTLED). */
    SETTLE
}

/**
 * The pure transition table. Returns the state after applying [action] to
 * [current] given the live readiness [ready]; returns [current] unchanged
 * when the action is not legal from here (a no-op is never an error —
 * callers may fire OBSERVE_READINESS every probe).
 *
 * Invariants enforced:
 * - COMMIT_CUTOVER requires BOTH READY_OBSERVED and [ready] == true — a
 *   race that lost readiness between observation and commit cannot flip.
 * - Readiness is only advisory in DUAL_RUNNING/READY_OBSERVED; it never
 *   moves a CUT_OVER/SETTLED install (post-flip the SDK IS the truth, so
 *   parity-vs-dashj is meaningless).
 * - ROLLBACK works only from CUT_OVER (not SETTLED — the horizon is final).
 */
fun nextCutoverState(
    current: CutoverState,
    action: CutoverAction,
    ready: Boolean
): CutoverState = when (action) {
    CutoverAction.OBSERVE_READINESS -> when (current) {
        CutoverState.DUAL_RUNNING -> if (ready) CutoverState.READY_OBSERVED else current
        CutoverState.READY_OBSERVED -> if (ready) current else CutoverState.DUAL_RUNNING
        // Post-flip: readiness is irrelevant, never regress.
        CutoverState.CUT_OVER, CutoverState.SETTLED -> current
    }
    CutoverAction.COMMIT_CUTOVER ->
        if (current == CutoverState.READY_OBSERVED && ready) CutoverState.CUT_OVER else current
    CutoverAction.ROLLBACK ->
        if (current == CutoverState.CUT_OVER) CutoverState.DUAL_RUNNING else current
    CutoverAction.SETTLE ->
        if (current == CutoverState.CUT_OVER) CutoverState.SETTLED else current
}

/**
 * The engine-start decision every start site consults (Phase 5d): may the
 * dashj L1 engine run this launch? True in every state EXCEPT the flipped
 * ones — post-cutover the SDK owns L1 and dashj must not start (never both
 * SPV engines live for one user). Kept trivial + pure so the start sites
 * stay obviously correct.
 *
 * NOTE: this gate is now LIVE-WIRED — a committed cutover actually holds the
 * dashj engine and routes L1 to the SDK. The engine-start site
 * ([de.schildbach.wallet.service.BlockchainServiceImpl] resolves
 * `dashjEngineMayStart` before starting the peergroup and skips it when
 * false), and the SDK send/balance/UI paths ([SdkL1SendService],
 * [SdkBlockchainStateService], [CutoverUiDataService], [CutoverTxSeamService],
 * `MainViewModel`) all consult this same predicate on the CUTOVER_STATE flow.
 * So a CUT_OVER install genuinely runs SDK-primary — this is no longer inert
 * instrumentation. The coordinator's suspend `dashjEngineMayStart()` adds a
 * fail-safe on top: it also allows dashj when committed but the SDK L1 engine
 * is disabled, so the wallet is never left with no L1 engine.
 */
fun dashjEngineMayStart(state: CutoverState): Boolean =
    state == CutoverState.DUAL_RUNNING || state == CutoverState.READY_OBSERVED
