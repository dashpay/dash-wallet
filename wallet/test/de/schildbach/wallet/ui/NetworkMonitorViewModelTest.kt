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

package de.schildbach.wallet.ui

import de.schildbach.wallet.service.L1SyncDetail
import de.schildbach.wallet.service.L1SyncStage
import de.schildbach.wallet.service.L1SyncStatusService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.R
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the Network Monitor's sync-status mapping: the
 * engine-neutral [L1SyncDetail] seam output (plus the dashj diagnostic
 * toggle) → the one [NetworkMonitorUIState] the screen renders.
 *
 * What this pins post-cutover: the screen shows LIVE engine data (stage,
 * scan positions, chainlock) instead of dashj's dead peergroup surfaces,
 * reports the peer limitation honestly (connection status derived from
 * the sync stage — the engine exposes no per-peer surface), and only
 * offers the legacy dashj peer/block panels while the diagnostic toggle
 * has un-held that engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun detail(
        stage: L1SyncStage = L1SyncStage.FILTERS,
        percentage: Int = 87,
        isSynced: Boolean = false,
        headerHeight: Long = 1_514_660,
        headerTarget: Long = 1_514_660,
        filterHeight: Long = 1_400_000,
        filterTarget: Long = 1_514_660,
        mnListHeight: Long = 1_514_600,
        chainLockHeight: Int = 1_514_650
    ) = L1SyncDetail(
        stage, percentage, isSynced,
        headerHeight, headerTarget, filterHeight, filterTarget,
        mnListHeight, chainLockHeight
    )

    // ── The pure mapping ──────────────────────────────────────────────

    @Test
    fun build_scanningDetail_carriesStagePercentAndScanPositions() {
        val state = buildNetworkMonitorUiState(detail(), dashjDiagnosticOn = false)

        assertEquals(R.string.network_monitor_stage_filters, state.stageRes)
        assertEquals(87, state.percentage)
        assertFalse(state.isSynced)
        assertEquals(1_514_660L, state.headerHeight)
        assertEquals(1_514_660L, state.headerTarget)
        assertEquals(1_400_000L, state.filterHeight)
        assertEquals(1_514_660L, state.filterTarget)
        assertEquals(1_514_600L, state.mnListHeight)
        assertEquals(1_514_650L, state.chainLockHeight)
    }

    @Test
    fun build_everyStageHasADisplayName() {
        // Exhaustive by construction (when over the enum), pinned so a new
        // stage without a string fails here instead of at render time.
        val seen = L1SyncStage.entries.map { stageNameRes(it) }.toSet()
        assertEquals(L1SyncStage.entries.size, seen.size)
    }

    @Test
    fun build_connectionStatus_isDerivedHonestlyFromTheStage() {
        // The engine exposes NO per-peer surface, so connectivity is
        // inferred: CONNECTING = the engine's own "no usable peers yet",
        // any active scan or synced state proves live peers.
        assertEquals(
            R.string.network_monitor_connection_idle,
            buildNetworkMonitorUiState(detail(stage = L1SyncStage.IDLE), false).connectionRes
        )
        assertEquals(
            R.string.network_monitor_connection_searching,
            buildNetworkMonitorUiState(detail(stage = L1SyncStage.CONNECTING), false).connectionRes
        )
        assertEquals(
            R.string.network_monitor_connection_error,
            buildNetworkMonitorUiState(detail(stage = L1SyncStage.ERROR), false).connectionRes
        )
        for (active in listOf(
            L1SyncStage.HEADERS, L1SyncStage.FILTER_HEADERS, L1SyncStage.MASTERNODE_LIST,
            L1SyncStage.FILTERS, L1SyncStage.SYNCED
        )) {
            assertEquals(
                R.string.network_monitor_connection_connected,
                buildNetworkMonitorUiState(detail(stage = active), false).connectionRes
            )
        }
    }

    @Test
    fun build_syncedDetail_readsSynced() {
        val state = buildNetworkMonitorUiState(
            detail(stage = L1SyncStage.SYNCED, percentage = 100, isSynced = true),
            dashjDiagnosticOn = false
        )
        assertEquals(R.string.network_monitor_stage_synced, state.stageRes)
        assertTrue(state.isSynced)
        assertEquals(100, state.percentage)
    }

    @Test
    fun build_diagnosticToggleAloneControlsTheDashjPanels() {
        assertFalse(buildNetworkMonitorUiState(detail(), dashjDiagnosticOn = false).showDashjPanels)
        assertTrue(buildNetworkMonitorUiState(detail(), dashjDiagnosticOn = true).showDashjPanels)
    }

    // ── The ViewModel wiring (seam flow → uiState) ────────────────────

    private fun viewModel(
        detailsFlow: kotlinx.coroutines.flow.Flow<L1SyncDetail>,
        diagnosticFlow: kotlinx.coroutines.flow.Flow<Boolean>
    ): NetworkMonitorViewModel {
        val service = mockk<L1SyncStatusService> {
            every { details } returns detailsFlow
        }
        val config = mockk<DashPayConfig> {
            every { observeDashjSyncDiagnostic() } returns diagnosticFlow
        }
        return NetworkMonitorViewModel(service, config)
    }

    @Test
    fun viewModel_mirrorsTheSeamFlowIntoUiState() = runTest {
        val viewModel = viewModel(
            detailsFlow = flowOf(detail(stage = L1SyncStage.SYNCED, percentage = 100, isSynced = true)),
            diagnosticFlow = flowOf(true)
        )
        val state = viewModel.uiState.value
        assertEquals(R.string.network_monitor_stage_synced, state.stageRes)
        assertTrue(state.isSynced)
        assertTrue(state.showDashjPanels)
    }

    @Test
    fun viewModel_startsFromTheInertDefaultUntilTheSeamEmits() = runTest {
        val details = MutableSharedFlow<L1SyncDetail>()
        val viewModel = viewModel(detailsFlow = details, diagnosticFlow = flowOf(false))

        val initial = viewModel.uiState.value
        assertEquals(R.string.network_monitor_stage_idle, initial.stageRes)
        assertEquals(R.string.network_monitor_connection_idle, initial.connectionRes)
        assertFalse(initial.showDashjPanels)
        assertEquals(0L, initial.headerHeight)

        details.emit(detail())
        assertEquals(R.string.network_monitor_stage_filters, viewModel.uiState.value.stageRes)
    }

    @Test
    fun viewModel_reactsToADiagnosticToggleFlip() = runTest {
        val diagnostic = MutableSharedFlow<Boolean>()
        val viewModel = viewModel(detailsFlow = flowOf(detail()), diagnosticFlow = diagnostic)

        diagnostic.emit(false)
        assertFalse(viewModel.uiState.value.showDashjPanels)

        diagnostic.emit(true)
        assertTrue(viewModel.uiState.value.showDashjPanels)
    }
}
