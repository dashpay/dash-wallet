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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.data.SyncStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-JVM tests for [SdkBlockchainStateService]: the cutover gate
 * (pre-cutover provably inert), and the EQUALITY-GATED 1 Hz propagation —
 * identical snapshots must not re-derive/re-write (the iOS-validated
 * pattern), while progress, tip-timestamp and stall EDGES must.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkBlockchainStateServiceTest {

    private val syncing = ShadowSyncProgress(ShadowSyncPhase.FILTERS, 1.0, 100, 100, 50, 100)

    private fun configWithState(state: String?): DashPayConfig = mockk {
        every { observe(DashPayConfig.CUTOVER_STATE) } returns flowOf(state)
    }

    private class Harness(
        testScope: TestScope,
        stored: String?,
        val progress: MutableStateFlow<ShadowSyncProgress>,
        val tip: MutableStateFlow<Long> = MutableStateFlow(0L),
        stallThresholdMs: Long = 5_000L
    ) {
        val updates = mutableListOf<SdkBlockchainStateUpdate>()
        val progressSubscriptions = AtomicInteger(0)
        val tipSubscriptions = AtomicInteger(0)

        val service = SdkBlockchainStateService(
            dashPayConfig = mockk {
                every { observe(DashPayConfig.CUTOVER_STATE) } returns flowOf(stored)
            },
            scope = testScope.backgroundScope,
            progress = flow {
                progressSubscriptions.incrementAndGet()
                emitAll(progress)
            },
            tipUnixSeconds = flow {
                tipSubscriptions.incrementAndGet()
                emitAll(tip)
            },
            applyUpdate = { updates += it },
            nowMs = { testScope.testScheduler.currentTime },
            stallThresholdMs = stallThresholdMs,
            tickIntervalMs = 1_000L
        )
    }

    // ── The cutover gate ──────────────────────────────────────────────

    @Test
    fun gate_activeOnlyWhenCutoverCommitted() = runBlocking {
        for ((stored, expected) in listOf(
            null to false,
            "DUAL_RUNNING" to false,
            "READY_OBSERVED" to false,
            "garbage" to false,
            "CUT_OVER" to true,
            "SETTLED" to true
        )) {
            val service = SdkBlockchainStateService(
                dashPayConfig = configWithState(stored),
                scope = this,
                progress = flowOf(),
                tipUnixSeconds = flowOf(),
                applyUpdate = {}
            )
            assertEquals("stored=$stored", expected, service.cutoverStateFeedActive().first())
        }
    }

    @Test
    fun preCutover_nothingCollectedNothingWritten() = runTest {
        val h = Harness(this, "DUAL_RUNNING", MutableStateFlow(syncing))
        h.service.start()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, h.progressSubscriptions.get())
        assertEquals(0, h.tipSubscriptions.get())
        assertTrue(h.updates.isEmpty())
    }

    // ── The equality gate ─────────────────────────────────────────────

    @Test
    fun postCutover_identicalSnapshotsPropagateExactlyOnce() = runTest {
        val h = Harness(this, "CUT_OVER", MutableStateFlow(syncing))
        h.service.start()
        runCurrent()
        assertEquals(1, h.updates.size)

        // Three more 1 Hz ticks with a byte-identical snapshot: gated.
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, h.updates.size)
        assertEquals(SyncStage.BLOCKS, h.updates.single().syncStage)
        assertFalse(h.updates.single().networkStalled)
    }

    @Test
    fun postCutover_progressChangePropagates() = runTest {
        val h = Harness(this, "CUT_OVER", MutableStateFlow(syncing))
        h.service.start()
        runCurrent()

        h.progress.value = syncing.copy(filterHeight = 60)
        runCurrent()
        assertEquals(2, h.updates.size)

        val synced = ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100)
        h.progress.value = synced
        runCurrent()
        assertEquals(3, h.updates.size)
        assertEquals(100, h.updates.last().percentageSync)
        assertEquals(SyncStage.COMPLETE, h.updates.last().syncStage)
    }

    @Test
    fun postCutover_tipTimestampChangePropagates() = runTest {
        val h = Harness(this, "CUT_OVER", MutableStateFlow(syncing))
        h.service.start()
        runCurrent()
        assertEquals(1, h.updates.size)

        // A new block lands: only the tip timestamp moves.
        h.tip.value = 1_753_000_100L
        runCurrent()
        assertEquals(2, h.updates.size)
        assertEquals(1_753_000_100L * 1000, h.updates.last().bestChainDateMs)
    }

    // ── The stall edge (derived NETWORK impediment) ───────────────────

    @Test
    fun postCutover_stalledScanRaisesThenClearsNetworkImpediment() = runTest {
        val h = Harness(this, "CUT_OVER", MutableStateFlow(syncing), stallThresholdMs = 5_000)
        h.service.start()
        runCurrent()
        assertEquals(1, h.updates.size)

        // Progress frozen mid-scan past the threshold: exactly ONE more
        // propagation — the stall EDGE — despite 8 further ticks.
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(2, h.updates.size)
        assertTrue(h.updates.last().networkStalled)

        // Progress resumes: the stall clears on the next snapshot.
        h.progress.value = syncing.copy(filterHeight = 70)
        runCurrent()
        assertEquals(3, h.updates.size)
        assertFalse(h.updates.last().networkStalled)
    }

    @Test
    fun postCutover_syncedNeverStalls() = runTest {
        val synced = ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100)
        val h = Harness(this, "CUT_OVER", MutableStateFlow(synced), stallThresholdMs = 5_000)
        h.service.start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, h.updates.size)
        assertFalse(h.updates.single().networkStalled)
    }

    @Test
    fun postCutover_errorPhaseStallsImmediately() = runTest {
        val error = ShadowSyncProgress(ShadowSyncPhase.ERROR, 0.0, 0, 0, 0, 0)
        val h = Harness(this, "CUT_OVER", MutableStateFlow(error))
        h.service.start()
        runCurrent()

        assertEquals(1, h.updates.size)
        assertTrue(h.updates.single().networkStalled)
        assertEquals(SyncStage.OFFLINE, h.updates.single().syncStage)
    }

    @Test
    fun postCutover_tipFeedFailureFallsBackToEstimator() = runTest {
        val updates = mutableListOf<SdkBlockchainStateUpdate>()
        val service = SdkBlockchainStateService(
            dashPayConfig = configWithState("CUT_OVER"),
            scope = backgroundScope,
            progress = MutableStateFlow(syncing),
            tipUnixSeconds = flow { throw IllegalStateException("SDK not ready") },
            applyUpdate = { updates += it },
            nowMs = { testScheduler.currentTime }
        )
        service.start()
        runCurrent()

        // The derivation still runs; the date comes from the estimator
        // (header gap is 0 here → "now").
        assertEquals(1, updates.size)
        assertEquals(testScheduler.currentTime, updates.single().bestChainDateMs)
    }
}
