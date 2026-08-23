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

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.L1SyncDetail
import de.schildbach.wallet.service.L1SyncStage
import de.schildbach.wallet.service.L1SyncStatusService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Everything the Network Monitor screen renders, in one state object.
 *
 * The screen consumes the ENGINE-NEUTRAL [L1SyncStatusService.details]
 * seam — it never learns which L1 engine produced the numbers. The one
 * engine-VISIBLE element is deliberate: [showDashjPanels] surfaces the
 * legacy dashj peer/block lists exactly when the dashj-sync DIAGNOSTIC
 * toggle (Tools) has un-held that engine — the one situation where a
 * developer wants to compare engines side by side. With the diagnostic
 * off those lists would render dashj's dead peergroup (empty peers, a
 * frozen block list), so they are replaced by a hint.
 *
 * @property headerHeight/@property headerTarget header-chain position
 *   (0 = unknown; target 0 = no known target — render height alone).
 * @property filterHeight/@property filterTarget wallet-relevant filter
 *   scan position (both 0 = the engine reports no filter pipeline).
 * @property chainLockHeight best PROVEN chainlocked height (monotonic
 *   lower bound), 0 = none observed yet.
 */
data class NetworkMonitorUIState(
    @StringRes val stageRes: Int = R.string.network_monitor_stage_idle,
    val percentage: Int = 0,
    val isSynced: Boolean = false,
    val headerHeight: Long = 0,
    val headerTarget: Long = 0,
    val filterHeight: Long = 0,
    val filterTarget: Long = 0,
    val mnListHeight: Long = 0,
    val chainLockHeight: Long = 0,
    @StringRes val connectionRes: Int = R.string.network_monitor_connection_idle,
    val showDashjPanels: Boolean = false
)

/** Neutral stage → its display name. Pure — host-testable. */
@StringRes
internal fun stageNameRes(stage: L1SyncStage): Int = when (stage) {
    L1SyncStage.IDLE -> R.string.network_monitor_stage_idle
    L1SyncStage.CONNECTING -> R.string.network_monitor_stage_connecting
    L1SyncStage.HEADERS -> R.string.network_monitor_stage_headers
    L1SyncStage.FILTER_HEADERS -> R.string.network_monitor_stage_filter_headers
    L1SyncStage.MASTERNODE_LIST -> R.string.network_monitor_stage_masternodes
    L1SyncStage.FILTERS -> R.string.network_monitor_stage_filters
    L1SyncStage.SYNCED -> R.string.network_monitor_stage_synced
    L1SyncStage.ERROR -> R.string.network_monitor_stage_error
    L1SyncStage.SETUP_RETRYING -> R.string.network_monitor_stage_setup_retrying
}

/**
 * The honest connection readout. The L1 engine exposes NO per-peer
 * surface (no addresses, versions, pings — not even a count; see the
 * screen's peer-note string), so connectivity is inferred from the sync
 * stage: CONNECTING is the engine's own "no usable peers yet" state, any
 * active scan or synced state proves live peers, and IDLE/ERROR say so.
 * Pure — host-testable.
 */
@StringRes
internal fun connectionStatusRes(stage: L1SyncStage): Int = when (stage) {
    L1SyncStage.IDLE -> R.string.network_monitor_connection_idle
    L1SyncStage.CONNECTING -> R.string.network_monitor_connection_searching
    L1SyncStage.ERROR -> R.string.network_monitor_connection_error
    // MO-995: the engine is down because wallet setup failed — the
    // connection row carries the actionable hint (unlocking the device is
    // the heal condition for the keystore-denied bind class).
    L1SyncStage.SETUP_RETRYING -> R.string.network_monitor_connection_setup_retrying
    else -> R.string.network_monitor_connection_connected
}

/** [L1SyncDetail] + the diagnostic toggle → the screen state. Pure — host-testable. */
internal fun buildNetworkMonitorUiState(
    detail: L1SyncDetail,
    dashjDiagnosticOn: Boolean
): NetworkMonitorUIState = NetworkMonitorUIState(
    stageRes = stageNameRes(detail.stage),
    percentage = detail.percentage,
    isSynced = detail.isSynced,
    headerHeight = detail.headerHeight,
    headerTarget = detail.headerTarget,
    filterHeight = detail.filterHeight,
    filterTarget = detail.filterTarget,
    mnListHeight = detail.mnListHeight,
    chainLockHeight = detail.chainLockHeight.toLong(),
    connectionRes = connectionStatusRes(detail.stage),
    showDashjPanels = dashjDiagnosticOn
)

@HiltViewModel
class NetworkMonitorViewModel @Inject constructor(
    l1SyncStatusService: L1SyncStatusService,
    dashPayConfig: DashPayConfig
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkMonitorUIState())
    val uiState: StateFlow<NetworkMonitorUIState> = _uiState.asStateFlow()

    init {
        combine(
            l1SyncStatusService.details,
            dashPayConfig.observeDashjSyncDiagnostic(),
            ::buildNetworkMonitorUiState
        )
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }
}
