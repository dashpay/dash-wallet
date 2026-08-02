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

package de.schildbach.wallet.service

import de.schildbach.wallet.service.platform.sdk.CutoverCoordinator
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.service.platform.sdk.shadowSyncPercent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The L1 sync state, as the UI needs to render it — with NO indication of
 * which engine produced it.
 *
 * @property isSynced the chain is caught up; hide the "Syncing N%" header,
 *   stop the blinking "Syncing balance" indicator, enable the sync-gated
 *   entry points (shortcut bar, CrowdNode staking, Join DashPay).
 * @property percentage 0..100 progress for the "Syncing N%" header; 0 means
 *   "no usable figure yet" and the header renders a bare "Syncing…".
 * @property isFailed sync is impeded (the error pane).
 */
data class L1SyncUiStatus(
    val isSynced: Boolean = false,
    val percentage: Int = 0,
    val isFailed: Boolean = false
)

/**
 * Whether the SDK L1 scan has caught up.
 *
 * THE ONE DEFINITION. Two consumers must flip in the same instant or the
 * user sees a contradiction: the home header's blinking "Syncing balance"
 * label, and `CutoverUiDataService.overlayTotalBalance`'s decision to
 * publish the live SDK figure instead of holding the last-known one. A
 * label over a live climbing figure — or a settled figure with the label
 * still blinking — is worse than either state alone. Both now derive from
 * here rather than from two hand-copied expressions.
 *
 * Uses [ShadowSyncProgress.scanCaughtUpToTip], not the SDK's never-latching
 * `synced`/phase == SYNCED: a live SPV perpetually chasing the moving tip
 * would otherwise read as un-synced forever. Pure — host-testable.
 */
fun sdkL1ScanCaughtUp(progress: ShadowSyncProgress): Boolean =
    progress.synced || progress.scanCaughtUpToTip

/**
 * dashj's own header percentage, replicating the historical rule that a
 * REPLAY reporting 100% is shown as 0 (a replay at "100%" has not started
 * re-scanning yet, and showing 100 would hide the header entirely).
 * Pure — host-testable.
 */
internal fun dashjSyncPercentage(state: BlockchainState?): Int = when {
    state == null -> 0
    state.replaying && state.percentageSync == 100 -> 0
    else -> state.percentageSync
}

/**
 * Merge the two engines' sync feeds into the one status the UI renders.
 * Pure — host-testable.
 *
 * `isFailed` comes from the persisted `BlockchainState` in BOTH regimes on
 * purpose: post-cutover `SdkBlockchainStateService` writes the SDK's own
 * progress-stall NETWORK impediment into that same row, so the failure
 * signal is engine-correct either way. (The UI used to read it
 * unconditionally from a dashj-only field ALONGSIDE a branched sync feed,
 * which post-cutover could paint the error pane from a stale dashj
 * impediment.)
 */
internal fun mergeL1SyncUiStatus(
    sdkOwnsL1: Boolean,
    sdkProgress: ShadowSyncProgress,
    dashjState: BlockchainState?
): L1SyncUiStatus = L1SyncUiStatus(
    isSynced = if (sdkOwnsL1) sdkL1ScanCaughtUp(sdkProgress) else dashjState?.isSynced() == true,
    percentage = if (sdkOwnsL1) shadowSyncPercent(sdkProgress) else dashjSyncPercentage(dashjState),
    isFailed = dashjState?.syncFailed() == true
)

/**
 * THE SEAM for "how far along is the L1 chain sync?".
 *
 * Before this existed, every screen that shows sync progress had to know
 * WHICH L1 engine was running: `MainViewModel` re-exported the engine-start
 * gate as a `sdkOwnsL1` UI flag and published two parallel pairs of feeds
 * (SDK progress vs dashj `BlockchainState`), and four Fragments each wrote
 * their own `if (sdkOwnsL1) … else …` to pick one — six branches, five
 * duplicated collector blocks, and a `LiveData<Boolean?>` / `StateFlow<Boolean>`
 * type mismatch at every call site. The engine choice is a seam concern; it
 * had leaked into the view layer.
 *
 * This collapses all of it into ONE flow. The engine choice is made here,
 * reactively ([CutoverCoordinator.sdkOwnsL1Flow], so a mid-launch
 * auto-commit is picked up without a relaunch — the old one-shot read was
 * resolved once at ViewModel construction), and nothing above the seam can
 * observe it.
 */
@Singleton
class L1SyncStatusService @Inject constructor(
    cutoverCoordinator: CutoverCoordinator,
    l1ShadowSyncService: L1ShadowSyncService,
    blockchainStateProvider: BlockchainStateProvider
) {
    /**
     * [sdkL1ScanCaughtUp] over the live SDK progress feed — published so
     * `CutoverUiDataService`'s balance hold and the home header's blinking
     * label share ONE source (see the function KDoc).
     */
    val sdkScanCaughtUp: Flow<Boolean> =
        l1ShadowSyncService.progress.map(::sdkL1ScanCaughtUp).distinctUntilChanged()

    /** The engine-agnostic L1 sync status every sync-aware screen renders. */
    val status: Flow<L1SyncUiStatus> =
        combine(
            cutoverCoordinator.sdkOwnsL1Flow(),
            l1ShadowSyncService.progress,
            blockchainStateProvider.observeState()
        ) { sdkOwnsL1, progress, state ->
            mergeL1SyncUiStatus(sdkOwnsL1, progress, state)
        }.distinctUntilChanged()
}
