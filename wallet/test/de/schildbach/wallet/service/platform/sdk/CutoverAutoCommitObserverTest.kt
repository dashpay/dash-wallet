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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 5d auto-commit trigger: the pure
 * [CaughtUpStabilityGate] and the [CutoverAutoCommitObserver]'s
 * reading→throttle→commit→standdown logic. The safety GATE lives in
 * [CutoverCoordinator.autoAdvanceToCutover] (tested in
 * CutoverCoordinatorTest); here the coordinator is mocked so we assert the
 * trigger CADENCE: it fires only on SUSTAINED caught-up, never on a single
 * reading, never while mid-scan/engine-down, and stands down after the flip.
 */
class CutoverAutoCommitObserverTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun status(state: CutoverState) = CutoverStatus(state, CutoverVerdict(emptySet()))

    private var now = 0L

    private fun observer(
        coordinator: CutoverCoordinator,
        requiredReadings: Int = 3,
        throttleMs: Long = 1_000L
    ): CutoverAutoCommitObserver {
        val shadow = mockk<L1ShadowSyncService>()
        every { shadow.progress } returns MutableStateFlow(ShadowSyncProgress.IDLE)
        return CutoverAutoCommitObserver(
            l1ShadowSyncService = shadow,
            coordinator = coordinator,
            scope = scope,
            requiredReadings = requiredReadings,
            attemptThrottleMs = throttleMs,
            nowMs = { now }
        )
    }

    // ── Pure stability gate ───────────────────────────────────────────

    @Test
    fun gate_armsOnlyAfterRequiredConsecutiveReadings() {
        val gate = CaughtUpStabilityGate(requiredReadings = 3)
        assertFalse(gate.onReading(true))
        assertFalse(gate.onReading(true))
        assertTrue(gate.onReading(true))
        // Stays armed while caught up.
        assertTrue(gate.onReading(true))
    }

    @Test
    fun gate_resetsTheStreakOnAnyNotCaughtUpReading() {
        val gate = CaughtUpStabilityGate(requiredReadings = 3)
        gate.onReading(true)
        gate.onReading(true)
        assertFalse(gate.onReading(false)) // mid-scan / engine-down dip resets
        assertEquals(0, gate.streak)
        assertFalse(gate.onReading(true))
        assertFalse(gate.onReading(true))
        assertTrue(gate.onReading(true)) // re-earned from scratch
    }

    // ── Observer trigger cadence ──────────────────────────────────────

    @Test
    fun onReading_doesNotFireOnASingleCaughtUpReading() = runBlocking {
        val coordinator = mockk<CutoverCoordinator>()
        val obs = observer(coordinator)
        now = 10_000L
        assertNull(obs.onReading(true))
        coVerify(exactly = 0) { coordinator.autoAdvanceToCutover() }
    }

    @Test
    fun onReading_firesOnceCaughtUpIsSustained() = runBlocking {
        val coordinator = mockk<CutoverCoordinator>()
        coEvery { coordinator.autoAdvanceToCutover() } returns status(CutoverState.DUAL_RUNNING)
        val obs = observer(coordinator)
        now = 10_000L
        assertNull(obs.onReading(true))
        assertNull(obs.onReading(true))
        assertEquals(CutoverState.DUAL_RUNNING, obs.onReading(true)) // armed → attempt
        coVerify(exactly = 1) { coordinator.autoAdvanceToCutover() }
    }

    @Test
    fun onReading_neverFiresWhileNotCaughtUp_evenAcrossManyReadings() = runBlocking {
        // A live shadow can report SYNCED without being caught up; the trigger
        // reads caught-up, so a stream that is never caught up NEVER fires.
        val coordinator = mockk<CutoverCoordinator>()
        val obs = observer(coordinator)
        now = 10_000L
        repeat(20) { assertNull(obs.onReading(false)) }
        coVerify(exactly = 0) { coordinator.autoAdvanceToCutover() }
    }

    @Test
    fun onReading_midScanDipResetsTheStreak_soCommitWaitsForAFreshRun() = runBlocking {
        val coordinator = mockk<CutoverCoordinator>()
        coEvery { coordinator.autoAdvanceToCutover() } returns status(CutoverState.DUAL_RUNNING)
        val obs = observer(coordinator)
        now = 10_000L
        obs.onReading(true)
        obs.onReading(true)
        obs.onReading(false) // dip
        obs.onReading(true)
        obs.onReading(true)
        coVerify(exactly = 0) { coordinator.autoAdvanceToCutover() } // only 2 since the dip
        obs.onReading(true) // now 3 in a row post-dip
        coVerify(exactly = 1) { coordinator.autoAdvanceToCutover() }
    }

    @Test
    fun onReading_throttlesRepeatedAttemptsWhenNotYetCommitted() = runBlocking {
        val coordinator = mockk<CutoverCoordinator>()
        coEvery { coordinator.autoAdvanceToCutover() } returns status(CutoverState.DUAL_RUNNING)
        val obs = observer(coordinator, throttleMs = 1_000L)
        now = 10_000L
        obs.onReading(true); obs.onReading(true); obs.onReading(true) // 1st attempt at t=10_000
        now = 10_500L
        assertNull(obs.onReading(true)) // within throttle → suppressed
        coVerify(exactly = 1) { coordinator.autoAdvanceToCutover() }
        now = 11_100L
        obs.onReading(true) // throttle elapsed → 2nd attempt
        coVerify(exactly = 2) { coordinator.autoAdvanceToCutover() }
    }

    @Test
    fun onReading_standsDownAfterTheFlipLands() = runBlocking {
        val coordinator = mockk<CutoverCoordinator>()
        coEvery { coordinator.autoAdvanceToCutover() } returns status(CutoverState.CUT_OVER)
        val obs = observer(coordinator)
        now = 10_000L
        obs.onReading(true); obs.onReading(true)
        assertEquals(CutoverState.CUT_OVER, obs.onReading(true)) // commits
        now = 100_000L
        // Latched: no further attempts even on sustained caught-up.
        repeat(5) { assertNull(obs.onReading(true)) }
        coVerify(exactly = 1) { coordinator.autoAdvanceToCutover() }
    }

    @Test
    fun rearmForNewWallet_clearsTheCommittedLatch_soTheNextWalletCanCommit() = runBlocking {
        val coordinator = mockk<CutoverCoordinator>()
        coEvery { coordinator.autoAdvanceToCutover() } returns status(CutoverState.CUT_OVER)
        val obs = observer(coordinator)
        now = 10_000L
        obs.onReading(true); obs.onReading(true); obs.onReading(true) // commits, latches
        assertNull(obs.onReading(true)) // latched

        obs.rearmForNewWallet() // wipe → new wallet

        // Streak + latch cleared: a fresh sustained run commits again.
        obs.onReading(true); obs.onReading(true)
        assertEquals(CutoverState.CUT_OVER, obs.onReading(true))
        coVerify(exactly = 2) { coordinator.autoAdvanceToCutover() }
    }
}
