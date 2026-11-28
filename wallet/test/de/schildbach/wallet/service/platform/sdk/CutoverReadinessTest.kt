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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 5d cutover decision table
 * ([evaluateCutoverReadiness]) — every at-risk state from the plan doc's
 * inventory maps to exactly one named blocker, and only a fully drained,
 * parity-proven, backed-up install reads Ready.
 */
class CutoverReadinessTest {

    private val window = CutoverPolicy.MIN_PARITY_WINDOW_MILLIS

    /** The production parity probe cadence the streak accumulates at (~10s). */
    private val probeIntervalMs = L1ShadowSyncService.PARITY_INTERVAL_MS

    /**
     * A streak satisfying all three parity rules, ending at [end]:
     * [CutoverPolicy.MIN_PARITY_STREAK] consecutive caught-up MATCH probes
     * spaced one probe interval apart, so the run spans well over the short
     * [CutoverPolicy.MIN_PARITY_WINDOW_MILLIS] floor.
     */
    private fun healthyStreak(end: Long = CutoverPolicy.MIN_PARITY_STREAK * probeIntervalMs): List<ParityObservation> =
        (CutoverPolicy.MIN_PARITY_STREAK - 1 downTo 0).map { back ->
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = end - back * probeIntervalMs)
        }

    private fun readyEvidence(
        parity: List<ParityObservation> = healthyStreak(),
        now: Long = (parity.lastOrNull()?.atElapsedMillis ?: 0L) + probeIntervalMs
    ) = CutoverEvidence(
        parityObservations = parity,
        unconfirmedSelfAuthoredTxs = 0,
        identityOperationInFlight = false,
        pendingShieldedLocks = 0,
        shieldedEnabled = true,
        shieldedReady = true,
        walletBackupExists = true,
        nowElapsedMillis = now
    )

    @Test
    fun fullyDrainedParityProvenInstall_isReady() {
        val verdict = evaluateCutoverReadiness(readyEvidence())
        assertTrue(verdict.blockers.toString(), verdict.ready)
    }

    @Test
    fun shortStreak_blocks() {
        val evidence = readyEvidence(
            parity = healthyStreak().takeLast(2)
        )
        val verdict = evaluateCutoverReadiness(evidence)
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
    }

    @Test
    fun singleMatch_neverConfirms_butNConsecutiveDo() {
        // The anti-blip contract: one (or two) caught-up MATCH probes are
        // never enough — only MIN_PARITY_STREAK consecutive confirm the flip.
        for (n in 1 until CutoverPolicy.MIN_PARITY_STREAK) {
            val verdict = evaluateCutoverReadiness(readyEvidence(parity = healthyStreak().takeLast(n)))
            assertTrue(
                "streak of $n must not confirm",
                verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT)
            )
        }
        // Exactly MIN_PARITY_STREAK consecutive caught-up matches → Ready.
        assertTrue(evaluateCutoverReadiness(readyEvidence()).ready)
    }

    @Test
    fun mismatchInTheTail_resetsTheStreak() {
        // A full run, then a diverging probe, then a fresh partial run: the
        // newest-tail streak is only 2 — one lucky run before a divergence
        // is not evidence, and the mismatch resets the confirmation.
        val parity = listOf(
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 0L),
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 60_000L),
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 120_000L),
            ParityObservation(caughtUp = true, match = false, atElapsedMillis = 180_000L), // divergence resets
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 240_000L),
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 300_000L)
        )
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = parity, now = 360_000L))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
    }

    @Test
    fun notCaughtUpProbeMidRun_resetsTheConfirmation() {
        // A mid-run not-caught-up probe (mid-scan dip) resets the streak the
        // same way a mismatch does: the tail after it is too short to confirm.
        val parity = listOf(
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 0L),
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 60_000L),
            ParityObservation(caughtUp = false, match = true, atElapsedMillis = 120_000L), // not-caught-up resets
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 180_000L),
            ParityObservation(caughtUp = true, match = true, atElapsedMillis = 240_000L)
        )
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = parity, now = 300_000L))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
    }

    @Test
    fun narrowWindow_blocks_evenWithEnoughProbes() {
        // MIN_PARITY_STREAK matches clustered within a sub-window interval:
        // streak length passes, but the wall-clock floor fails — a coincidental
        // burst is not real sustained parity.
        val parity = listOf(
            ParityObservation(true, true, atElapsedMillis = 0L),
            ParityObservation(true, true, atElapsedMillis = window / 3),
            ParityObservation(true, true, atElapsedMillis = window - 1)
        )
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = parity, now = window))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_WINDOW_TOO_NARROW))
        assertFalse(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
    }

    @Test
    fun staleEvidence_blocks_evenWithAPerfectStreak() {
        val streak = healthyStreak()
        val verdict = evaluateCutoverReadiness(
            readyEvidence(
                parity = streak,
                now = streak.last().atElapsedMillis + CutoverPolicy.MAX_PARITY_AGE_MILLIS + 1
            )
        )
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_EVIDENCE_STALE))
    }

    @Test
    fun noObservationsAtAll_blocksAsShortAndStale() {
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = emptyList(), now = 0L))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_EVIDENCE_STALE))
    }

    @Test
    fun eachDrainableState_mapsToItsOwnBlocker() {
        assertEquals(
            setOf(CutoverBlocker.UNCONFIRMED_SELF_AUTHORED_TXS),
            evaluateCutoverReadiness(readyEvidence().copy(unconfirmedSelfAuthoredTxs = 2)).blockers
        )
        assertEquals(
            setOf(CutoverBlocker.IDENTITY_OPERATION_IN_FLIGHT),
            evaluateCutoverReadiness(readyEvidence().copy(identityOperationInFlight = true)).blockers
        )
        assertEquals(
            setOf(CutoverBlocker.PENDING_SHIELDED_LOCKS),
            evaluateCutoverReadiness(readyEvidence().copy(pendingShieldedLocks = 1)).blockers
        )
        assertEquals(
            setOf(CutoverBlocker.NO_WALLET_BACKUP),
            evaluateCutoverReadiness(readyEvidence().copy(walletBackupExists = false)).blockers
        )
    }

    @Test
    fun shieldedRuntime_onlyConsultedWhenEnabled() {
        assertEquals(
            setOf(CutoverBlocker.SHIELDED_RUNTIME_NOT_READY),
            evaluateCutoverReadiness(readyEvidence().copy(shieldedReady = false)).blockers
        )
        // Shielded off: an un-ready runtime is irrelevant.
        assertTrue(
            evaluateCutoverReadiness(
                readyEvidence().copy(shieldedEnabled = false, shieldedReady = false)
            ).ready
        )
    }

    // ── ParityStreakRecorder ─────────────────────────────────────────────

    private fun report(
        match: Boolean = true,
        confirmedMatch: Boolean = true,
        txCountsMatch: Boolean = true,
        synced: Boolean = true
    ) = ParityReport(
        balancesMatch = match,
        sdkDuffs = 100L,
        dashjDuffs = if (match) 100L else 99L,
        sdkTxCount = 5,
        dashjTxCount = if (txCountsMatch) 5 else 4,
        sdkSynced = synced,
        timestampMs = 0L,
        confirmedBalancesMatch = confirmedMatch,
        sdkConfirmedDuffs = 100L,
        dashjAvailableDuffs = if (confirmedMatch) 100L else 98L
    )

    @Test
    fun recorder_matchRequiresAllThreeDimensions() {
        val recorder = ParityStreakRecorder()
        recorder.record(report(), caughtUp = true, atElapsedMillis = 1L)
        recorder.record(report(confirmedMatch = false), caughtUp = true, atElapsedMillis = 2L)
        recorder.record(report(txCountsMatch = false), caughtUp = true, atElapsedMillis = 3L)

        val snapshot = recorder.snapshot()
        assertEquals(listOf(true, false, false), snapshot.map { it.match })
        assertTrue(snapshot.all { it.caughtUp })
    }

    @Test
    fun recorder_recordsTheCaughtUpBit_notTheSyncedFlag() {
        // The report's own SYNCED flag is irrelevant to the streak: a live
        // shadow is caught up while never latching SYNCED, and vice versa a
        // SYNCED-but-not-caught-up snapshot must not count.
        val recorder = ParityStreakRecorder()
        recorder.record(report(synced = false), caughtUp = true, atElapsedMillis = 1L)
        recorder.record(report(synced = true), caughtUp = false, atElapsedMillis = 2L)

        assertEquals(listOf(true, false), recorder.snapshot().map { it.caughtUp })
    }

    @Test
    fun recorder_boundsTheWindow_andClears() {
        val recorder = ParityStreakRecorder(maxObservations = 3)
        (1L..5L).forEach { recorder.record(report(), caughtUp = true, atElapsedMillis = it) }
        assertEquals(listOf(3L, 4L, 5L), recorder.snapshot().map { it.atElapsedMillis })

        recorder.clear()
        assertTrue(recorder.snapshot().isEmpty())
    }

    // ── Metadata orphan audit ────────────────────────────────────────────

    @Test
    fun orphanAudit_partitionsRealLossFromPreexistingGarbage() {
        val audit = auditMetadataOrphans(
            metadataTxids = setOf("a", "b", "c", "d"),
            sdkTxids = setOf("a", "b"),
            dashjTxids = setOf("a", "b", "c")
        )
        assertEquals(4, audit.totalMetadataRows)
        // "c": dashj knows it, the SDK does not — REAL loss at cutover.
        assertEquals(setOf("c"), audit.missingFromSdk)
        // "d": neither engine knows it — pre-existing garbage, not a blocker.
        assertEquals(setOf("d"), audit.missingFromBoth)
        assertFalse(audit.clean)

        assertTrue(
            auditMetadataOrphans(
                metadataTxids = setOf("a"),
                sdkTxids = setOf("a"),
                dashjTxids = setOf("a")
            ).clean
        )
    }

    @Test
    fun blockersAccumulate_verdictIsATodoList() {
        val verdict = evaluateCutoverReadiness(
            readyEvidence(parity = emptyList(), now = 0L).copy(
                unconfirmedSelfAuthoredTxs = 1,
                identityOperationInFlight = true,
                pendingShieldedLocks = 3,
                walletBackupExists = false,
                shieldedReady = false
            )
        )
        assertEquals(
            setOf(
                CutoverBlocker.PARITY_STREAK_TOO_SHORT,
                CutoverBlocker.PARITY_EVIDENCE_STALE,
                CutoverBlocker.UNCONFIRMED_SELF_AUTHORED_TXS,
                CutoverBlocker.IDENTITY_OPERATION_IN_FLIGHT,
                CutoverBlocker.PENDING_SHIELDED_LOCKS,
                CutoverBlocker.SHIELDED_RUNTIME_NOT_READY,
                CutoverBlocker.NO_WALLET_BACKUP
            ),
            verdict.blockers
        )
        assertFalse(verdict.ready)
    }
}
