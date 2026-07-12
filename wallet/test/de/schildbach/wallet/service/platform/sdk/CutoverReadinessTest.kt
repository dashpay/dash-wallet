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

    private val tenMinutes = CutoverPolicy.MIN_PARITY_WINDOW_MILLIS

    /** A streak satisfying all three parity rules, ending at [end]. */
    private fun healthyStreak(end: Long = tenMinutes + 60_000L): List<ParityObservation> =
        listOf(
            ParityObservation(synced = true, match = true, atElapsedMillis = end - tenMinutes - 30_000L),
            ParityObservation(synced = true, match = true, atElapsedMillis = end - tenMinutes / 2),
            ParityObservation(synced = true, match = true, atElapsedMillis = end)
        )

    private fun readyEvidence(
        parity: List<ParityObservation> = healthyStreak(),
        now: Long = (parity.lastOrNull()?.atElapsedMillis ?: 0L) + 60_000L
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
    fun mismatchInTheTail_resetsTheStreak() {
        // Three matches, then a diverging probe, then two matches: the
        // newest-tail streak is 2 — one lucky run before a divergence is
        // not evidence.
        val end = tenMinutes * 3
        val parity = healthyStreak(end = tenMinutes + 60_000L) +
            ParityObservation(synced = true, match = false, atElapsedMillis = tenMinutes * 2) +
            listOf(
                ParityObservation(synced = true, match = true, atElapsedMillis = end - 30_000L),
                ParityObservation(synced = true, match = true, atElapsedMillis = end)
            )
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = parity, now = end + 1))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
    }

    @Test
    fun unsyncedProbesDoNotCount() {
        val end = tenMinutes + 60_000L
        val parity = listOf(
            ParityObservation(synced = false, match = true, atElapsedMillis = 0L),
            ParityObservation(synced = false, match = true, atElapsedMillis = end - tenMinutes / 2),
            ParityObservation(synced = true, match = true, atElapsedMillis = end)
        )
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = parity, now = end + 1))
        assertTrue(verdict.blockers.contains(CutoverBlocker.PARITY_STREAK_TOO_SHORT))
    }

    @Test
    fun narrowWindow_blocks_evenWithEnoughProbes() {
        // Three matches within one minute: streak length passes, span fails.
        val parity = listOf(
            ParityObservation(true, true, atElapsedMillis = 0L),
            ParityObservation(true, true, atElapsedMillis = 30_000L),
            ParityObservation(true, true, atElapsedMillis = 60_000L)
        )
        val verdict = evaluateCutoverReadiness(readyEvidence(parity = parity, now = 61_000L))
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
