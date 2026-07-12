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
 * Phase 5d (docs/kotlin-sdk-migration-plan.md): the PURE gate deciding
 * whether this install may cut over from dashj to the SDK as the L1
 * source of truth without losing data. Evidence in, verdict out — no
 * Android, no I/O, host-JVM testable. The coordinator (and, first, a
 * debug Settings readout) supplies [CutoverEvidence] from live services
 * and acts on the verdict; this file owns only the DECISION TABLE, so
 * the cutover policy is reviewable and testable in one place.
 *
 * Design rule: every blocker names the EXACT at-risk state from the plan
 * doc's inventory — a Blocked verdict is a to-do list, not a shrug.
 */

/** One point-in-time L1 parity observation (from `L1Parity` probe reports). */
data class ParityObservation(
    /** Both engines fully synced when the probe ran. */
    val synced: Boolean,
    /** estimated, confirmed AND tx-count all equal between dashj and the SDK. */
    val match: Boolean,
    /** Elapsed-realtime millis of the observation (monotonic, not wall clock). */
    val atElapsedMillis: Long
)

/**
 * Everything the decision table looks at. Collectors fill this from the
 * live services; tests construct it directly.
 */
data class CutoverEvidence(
    /** Recent parity observations, oldest first (bounded window kept by the recorder). */
    val parityObservations: List<ParityObservation>,
    /** Unconfirmed SELF-AUTHORED dashj transactions (mempool-only — invisible to a rescan). */
    val unconfirmedSelfAuthoredTxs: Int,
    /**
     * The legacy identity state machine is mid-flight (creationState past
     * NONE but not DONE/DONE_AND_DISMISS, or a username request awaiting
     * submit/vote resolution that the legacy machine must drive).
     */
    val identityOperationInFlight: Boolean,
    /** Tracked, still-resumable shielded top-up asset locks (must be consumed or void). */
    val pendingShieldedLocks: Int,
    /** `USE_KOTLIN_SDK_SHIELDED` is on for this install. */
    val shieldedEnabled: Boolean,
    /** Shielded runtime status — only consulted when [shieldedEnabled]. */
    val shieldedReady: Boolean,
    /** A current `.wallet` backup exists on disk (the rollback escape hatch). */
    val walletBackupExists: Boolean,
    /** "Now" on the same monotonic clock as [ParityObservation.atElapsedMillis]. */
    val nowElapsedMillis: Long
)

/** Why the cutover is blocked — names match the plan doc's at-risk inventory. */
enum class CutoverBlocker {
    /** Fewer than [CutoverPolicy.MIN_PARITY_STREAK] consecutive synced MATCH probes. */
    PARITY_STREAK_TOO_SHORT,

    /** The streak spans less than [CutoverPolicy.MIN_PARITY_WINDOW_MILLIS]. */
    PARITY_WINDOW_TOO_NARROW,

    /** The newest observation is older than [CutoverPolicy.MAX_PARITY_AGE_MILLIS]. */
    PARITY_EVIDENCE_STALE,

    /** Mempool-only self-authored dashj txs exist — a rescan cannot see them. */
    UNCONFIRMED_SELF_AUTHORED_TXS,

    /** The legacy identity/username state machine is mid-flight. */
    IDENTITY_OPERATION_IN_FLIGHT,

    /** Resumable shielded top-up locks remain undrained. */
    PENDING_SHIELDED_LOCKS,

    /** Shielded features are on but the runtime never reached READY. */
    SHIELDED_RUNTIME_NOT_READY,

    /** No current wallet-file backup — the rollback escape hatch is missing. */
    NO_WALLET_BACKUP
}

/** The verdict: [ready] iff [blockers] is empty. */
data class CutoverVerdict(val blockers: Set<CutoverBlocker>) {
    val ready: Boolean get() = blockers.isEmpty()
}

/**
 * The policy constants, in one place so a threshold change is one diff.
 * Values are deliberately conservative for the first live use; they are
 * tuning inputs, not correctness inputs — the BLOCKER SET is the contract.
 */
object CutoverPolicy {
    /** Consecutive synced+match probes required (newest-tail run). */
    const val MIN_PARITY_STREAK = 3

    /** The matching streak must span at least this long (millis). */
    const val MIN_PARITY_WINDOW_MILLIS = 10 * 60 * 1000L

    /** The newest observation must be at most this old (millis). */
    const val MAX_PARITY_AGE_MILLIS = 5 * 60 * 1000L
}

/**
 * The decision table. Pure; every rule keyed to one [CutoverBlocker].
 *
 * Parity rules look at the NEWEST-TAIL streak: the run of consecutive
 * observations at the end of [CutoverEvidence.parityObservations] that
 * are both `synced` and `match`. A single non-matching probe anywhere in
 * the tail resets the streak — one lucky MATCH is not evidence, and a
 * mismatch after matches means the engines diverged NOW.
 */
fun evaluateCutoverReadiness(evidence: CutoverEvidence): CutoverVerdict {
    val blockers = mutableSetOf<CutoverBlocker>()

    // ── Parity streak (rules 1–3) ─────────────────────────────────────
    val tailStreak = evidence.parityObservations.takeLastWhile { it.synced && it.match }
    if (tailStreak.size < CutoverPolicy.MIN_PARITY_STREAK) {
        blockers += CutoverBlocker.PARITY_STREAK_TOO_SHORT
    } else {
        val span = tailStreak.last().atElapsedMillis - tailStreak.first().atElapsedMillis
        if (span < CutoverPolicy.MIN_PARITY_WINDOW_MILLIS) {
            blockers += CutoverBlocker.PARITY_WINDOW_TOO_NARROW
        }
    }
    val newest = evidence.parityObservations.lastOrNull()
    if (newest == null ||
        evidence.nowElapsedMillis - newest.atElapsedMillis > CutoverPolicy.MAX_PARITY_AGE_MILLIS
    ) {
        blockers += CutoverBlocker.PARITY_EVIDENCE_STALE
    }

    // ── Drained-operation rules (4–6) ─────────────────────────────────
    if (evidence.unconfirmedSelfAuthoredTxs > 0) {
        blockers += CutoverBlocker.UNCONFIRMED_SELF_AUTHORED_TXS
    }
    if (evidence.identityOperationInFlight) {
        blockers += CutoverBlocker.IDENTITY_OPERATION_IN_FLIGHT
    }
    if (evidence.pendingShieldedLocks > 0) {
        blockers += CutoverBlocker.PENDING_SHIELDED_LOCKS
    }

    // ── Runtime + escape hatch (7–8) ──────────────────────────────────
    if (evidence.shieldedEnabled && !evidence.shieldedReady) {
        blockers += CutoverBlocker.SHIELDED_RUNTIME_NOT_READY
    }
    if (!evidence.walletBackupExists) {
        blockers += CutoverBlocker.NO_WALLET_BACKUP
    }

    return CutoverVerdict(blockers)
}
