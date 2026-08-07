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
    /**
     * The SDK's wallet-relevant compact-filter scan had CAUGHT UP TO THE
     * CHAIN TIP when the probe ran ([ShadowSyncProgress.scanCaughtUpToTip]) —
     * deliberately NOT the SDK's own SYNCED flag ([ShadowSyncProgress.synced]).
     * A live shadow perpetually chases the moving tip, so its overall state
     * never latches SYNCED (a new block every ~2.5 min bumps the target and
     * drops it back to SYNCING before it can latch — see
     * [ShadowSyncProgress.scanCaughtUpToTip]). Gating the cutover streak on
     * SYNCED would therefore keep it empty FOREVER on a real post-cutover
     * wallet, so no auto- or debug commit could ever fire. Caught-up is the
     * reliable, fund-safe readiness signal a live shadow can actually reach
     * (the same one the shielded funding gate switched to).
     */
    val caughtUp: Boolean,
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
    /** Fewer than [CutoverPolicy.MIN_PARITY_STREAK] consecutive caught-up MATCH probes. */
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
    /**
     * Consecutive caught-up+MATCH probes required in the newest-tail run —
     * the ANTI-BLIP CONFIRMATION and the load-bearing sustained-parity gate.
     *
     * This is the deterministic "N consecutive matches" formulation (rather
     * than a long wall-clock window): a single fleeting mid-scan parity blip
     * can never trigger the cutover, because THREE consecutive probes must
     * all be caught-up AND agree on estimated+confirmed+txCount, and any one
     * not-caught-up/mismatch probe resets the run to zero (see
     * [evaluateCutoverReadiness]). At the ~10s parity probe cadence
     * ([L1ShadowSyncService.PARITY_INTERVAL_MS]) three consecutive probes
     * span ~20s, so the cutover flips within ~20-30s of the SDK genuinely
     * reaching caught-up parity — not the former ~10 minutes.
     */
    const val MIN_PARITY_STREAK = 3

    /**
     * A short wall-clock FLOOR under the streak, so the confirmation cannot
     * be satisfied by a burst of samples packed into a sub-second interval
     * (defence-in-depth if the probe cadence is ever lowered further). The
     * primary anti-blip gate is [MIN_PARITY_STREAK]; at the ~10s probe cadence
     * a 3-probe streak already spans ~20s, so this floor is never the binding
     * constraint in practice — it just guarantees the streak represents real
     * elapsed time, not a coincidental cluster.
     */
    const val MIN_PARITY_WINDOW_MILLIS = 15 * 1000L

    /** The newest observation must be at most this old (millis). */
    const val MAX_PARITY_AGE_MILLIS = 5 * 60 * 1000L
}

/**
 * Bounded window of parity observations feeding [CutoverEvidence]. One
 * instance lives on the shadow-sync service; [record] is called once per
 * probe (with a MONOTONIC elapsed-realtime stamp), [clear] whenever the
 * shadow state is reset (stale evidence from before a reset must never
 * count toward a cutover). Thread-safe; pure Kotlin (host-testable).
 *
 * An observation is a MATCH only when ALL THREE parity dimensions agree
 * (estimated, confirmed, tx count) — the same bar the funding gate uses —
 * and counts toward the cutover streak only when the scan is also CAUGHT
 * UP TO TIP ([caughtUp], from [ShadowSyncProgress.scanCaughtUpToTip], NOT
 * the never-latching SYNCED flag — see [ParityObservation.caughtUp]).
 */
class ParityStreakRecorder(private val maxObservations: Int = 64) {
    private val window = ArrayDeque<ParityObservation>()

    @Synchronized
    fun record(report: ParityReport, caughtUp: Boolean, atElapsedMillis: Long) {
        window.addLast(
            ParityObservation(
                caughtUp = caughtUp,
                match = report.balancesMatch && report.confirmedBalancesMatch && report.txCountsMatch,
                atElapsedMillis = atElapsedMillis
            )
        )
        while (window.size > maxObservations) window.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<ParityObservation> = window.toList()

    @Synchronized
    fun clear() = window.clear()
}

/**
 * Data-survival audit for the "keyed carry-over" inventory class: app
 * Room rows are keyed by txid, so a row whose txid the post-cutover
 * engine does not know is ORPHANED (its memo/fiat/gift-card context
 * silently vanishes from the UI). Pure; collectors feed it the three
 * txid sets. [missingFromSdk] non-empty blocks cutover for real
 * metadata; [missingFromBoth] is pre-existing garbage (safe to ignore
 * but reported for completeness).
 */
data class MetadataOrphanAudit(
    val totalMetadataRows: Int,
    val missingFromSdk: Set<String>,
    val missingFromBoth: Set<String>
) {
    val clean: Boolean get() = missingFromSdk.isEmpty()
}

fun auditMetadataOrphans(
    metadataTxids: Set<String>,
    sdkTxids: Set<String>,
    dashjTxids: Set<String>
): MetadataOrphanAudit {
    val notInSdk = metadataTxids - sdkTxids
    val inNeither = notInSdk - dashjTxids
    return MetadataOrphanAudit(
        totalMetadataRows = metadataTxids.size,
        // Rows dashj knows but the SDK does not = REAL loss at cutover.
        missingFromSdk = notInSdk - inNeither,
        missingFromBoth = inNeither
    )
}

/**
 * The decision table. Pure; every rule keyed to one [CutoverBlocker].
 *
 * Parity rules look at the NEWEST-TAIL streak: the run of consecutive
 * observations at the end of [CutoverEvidence.parityObservations] that
 * are both `caughtUp` and `match`. A single non-matching or not-caught-up
 * probe anywhere in the tail resets the streak — one lucky MATCH is not
 * evidence, and a mismatch after matches means the engines diverged NOW.
 */
fun evaluateCutoverReadiness(evidence: CutoverEvidence): CutoverVerdict {
    val blockers = mutableSetOf<CutoverBlocker>()

    // ── Parity streak (rules 1–3) ─────────────────────────────────────
    val tailStreak = evidence.parityObservations.takeLastWhile { it.caughtUp && it.match }
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
