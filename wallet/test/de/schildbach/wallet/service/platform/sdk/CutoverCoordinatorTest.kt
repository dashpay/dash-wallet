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
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the [CutoverCoordinator] transitions added for the
 * automatic + restore/new-wallet cutover: the immediate fresh-wallet
 * commit (self-gated on the SDK L1 flag), the per-wallet wipe reset, and
 * the combined [CutoverCoordinator.autoAdvanceToCutover] path the
 * auto-commit observer drives.
 */
class CutoverCoordinatorTest {

    private val tenMinutes = CutoverPolicy.MIN_PARITY_WINDOW_MILLIS

    /** A fully drained, caught-up, parity-proven, backed-up evidence set → Ready. */
    private fun readyEvidence(): CutoverEvidence {
        val end = tenMinutes + 60_000L
        return CutoverEvidence(
            parityObservations = listOf(
                ParityObservation(caughtUp = true, match = true, atElapsedMillis = end - tenMinutes - 30_000L),
                ParityObservation(caughtUp = true, match = true, atElapsedMillis = end - tenMinutes / 2),
                ParityObservation(caughtUp = true, match = true, atElapsedMillis = end)
            ),
            unconfirmedSelfAuthoredTxs = 0,
            identityOperationInFlight = false,
            pendingShieldedLocks = 0,
            shieldedEnabled = true,
            shieldedReady = true,
            walletBackupExists = true,
            nowElapsedMillis = end + 60_000L
        )
    }

    /**
     * A [CutoverCoordinator] over a STATEFUL in-memory CUTOVER_STATE (reads
     * see writes, so the two-step advisory→commit path works), the SDK L1
     * flag, and a fixed evidence set. Returns the coordinator plus a live
     * view of the stored state.
     */
    private fun coordinator(
        stored: String? = null,
        flag: Boolean? = true,
        evidence: CutoverEvidence = readyEvidence()
    ): Pair<CutoverCoordinator, () -> String?> {
        var current = stored
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns flag
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg()
            Unit
        }
        val collector = mockk<CutoverEvidenceCollector>()
        coEvery { collector.collect() } returns evidence
        return CutoverCoordinator(config, collector) to { current }
    }

    // ── Restore/new-wallet immediate commit ───────────────────────────

    @Test
    fun commitForFreshWalletSetup_committsImmediately_whenFlagOn() = runBlocking {
        val (coordinator, stored) = coordinator(stored = null, flag = true)
        val status = coordinator.commitForFreshWalletSetup()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        // No dashj L1 engine post-commit: the SDK owns L1 from the start.
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun commitForFreshWalletSetup_isNoOp_whenSdkL1FlagOff() = runBlocking {
        // Flag off = SDK L1 engine inactive; holding dashj would leave NO L1
        // engine, so the fresh wallet must stay dual-running on dashj.
        val (coordinator, stored) = coordinator(stored = null, flag = false)
        val status = coordinator.commitForFreshWalletSetup()
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(null, stored())
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun commitForFreshWalletSetup_doesNotClobberSettled() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.SETTLED.name, flag = true)
        val status = coordinator.commitForFreshWalletSetup()
        assertEquals(CutoverState.SETTLED, status.state)
        assertEquals(CutoverState.SETTLED.name, stored())
    }

    @Test
    fun commitForFreshWalletSetup_doesNotReadinessGate() = runBlocking {
        // Evidence is NOT ready (no parity), yet a fresh wallet commits anyway:
        // there is no synced balance to protect.
        val notReady = readyEvidence().copy(parityObservations = emptyList())
        val (coordinator, stored) = coordinator(stored = null, flag = true, evidence = notReady)
        assertEquals(CutoverState.CUT_OVER, coordinator.commitForFreshWalletSetup().state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    // ── Per-wallet wipe reset ─────────────────────────────────────────

    @Test
    fun resetForWalletWipe_putsCommittedStateBackToDualRunning() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.CUT_OVER.name)
        val status = coordinator.resetForWalletWipe()
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(CutoverState.DUAL_RUNNING.name, stored())
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun resetForWalletWipe_isNoOp_whenAlreadyDualRunning() = runBlocking {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } returns null
        val setSlot: CapturingSlot<String> = slot()
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, capture(setSlot)) } just Runs
        val collector = mockk<CutoverEvidenceCollector>()
        val coordinator = CutoverCoordinator(config, collector)

        assertEquals(CutoverState.DUAL_RUNNING, coordinator.resetForWalletWipe().state)
        // No write when there is nothing to reset.
        coVerify(exactly = 0) { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) }
    }

    // ── Automatic advisory→commit path ────────────────────────────────

    @Test
    fun autoAdvanceToCutover_commits_whenFullyReady() = runBlocking {
        val (coordinator, stored) = coordinator(stored = null, evidence = readyEvidence())
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    @Test
    fun autoAdvanceToCutover_staysDualRunning_whenBlocked() = runBlocking {
        // A single blocker (pending shielded lock) → never leaves DUAL_RUNNING.
        val blocked = readyEvidence().copy(pendingShieldedLocks = 1)
        val (coordinator, stored) = coordinator(stored = null, evidence = blocked)
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(null, stored())
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun autoAdvanceToCutover_isIdempotentOnceCommitted() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.CUT_OVER.name)
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }
}
