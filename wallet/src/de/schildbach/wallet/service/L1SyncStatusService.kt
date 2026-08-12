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
import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.service.platform.sdk.shadowSyncPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 *
 * The `synced` (SDK-latched SYNCED phase) arm is ALSO gated on the block
 * pipeline not provably lagging ([ShadowSyncProgress.blockPipelineLagging]):
 * the engine's overall phase was observed holding SYNCED while an armed
 * rescan replayed the whole filter range and the block/tx pipeline was
 * still churning — the premature l1Synced=true that persisted a partial
 * balance in the field. An UNKNOWN cursor never counts as lagging, so this
 * cannot deadlock a genuinely-at-tip wallet (see the predicate's KDoc).
 */
fun sdkL1ScanCaughtUp(progress: ShadowSyncProgress): Boolean =
    (progress.synced || progress.scanCaughtUpToTip) && !progress.blockPipelineLagging

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
 *
 * [platformStarved] ORs in the sustained masternode-list starvation verdict
 * ([platformMasternodeListStarved] held past its grace window) — a
 * PLATFORM-side outage the L1 signals above cannot see. The progress-stall
 * impediment's clock is reset by ANY progress change, and post-sync every
 * new block ticks the header/filter heights, so an engine stuck without a
 * masternode list (no DAPI possible) reads as "not stalled" within about a
 * block interval of each banner appearance — observed live: the banner
 * cleared on its own while the masternode list was still empty and DAPI had
 * made zero calls for over half an hour. The starvation verdict holds for
 * as long as its condition does, so the banner cannot self-clear while the
 * outage persists.
 */
internal fun mergeL1SyncUiStatus(
    sdkOwnsL1: Boolean,
    sdkProgress: ShadowSyncProgress,
    dashjState: BlockchainState?,
    platformStarved: Boolean = false
): L1SyncUiStatus = L1SyncUiStatus(
    isSynced = if (sdkOwnsL1) sdkL1ScanCaughtUp(sdkProgress) else dashjState?.isSynced() == true,
    percentage = if (sdkOwnsL1) shadowSyncPercent(sdkProgress) else dashjSyncPercentage(dashjState),
    isFailed = dashjState?.syncFailed() == true || platformStarved
)

/**
 * The INSTANTANEOUS masternode-list starvation condition: the SDK owns L1,
 * the wallet-relevant scan has caught up (the header shows "synced"), and
 * neither the live engine feed nor the persisted row has EVER seen a
 * masternode list — meaning no quorum knowledge and no DAPI address list,
 * so every Platform feature is unreachable regardless of how healthy the
 * L1 chain looks. Pure — host-testable.
 *
 * Uses the max of the live and persisted heights, exactly like
 * [mergeL1SyncDetail]: on a warm start of a HEALTHY wallet the engine's
 * live height is legitimately 0 for a while, but the row (preserved on the
 * don't-regress-on-unknown rule) still carries the previous session's
 * height — so only wallets that have never synced a masternode list at all
 * (the stranded-fresh-wallet shape) can satisfy this.
 *
 * Held-time is deliberately NOT part of this predicate — the caller wraps
 * it in [sustainedPlatformStarvation] so a normal startup (where the list
 * is legitimately absent for a couple of minutes after the scan catches
 * up) never flashes the banner.
 */
internal fun platformMasternodeListStarved(
    sdkOwnsL1: Boolean,
    sdkProgress: ShadowSyncProgress,
    dashjState: BlockchainState?
): Boolean = sdkOwnsL1 &&
    sdkL1ScanCaughtUp(sdkProgress) &&
    maxOf(sdkProgress.mnListHeight, (dashjState?.mnlistHeight ?: 0).toLong()) <= 0L

/**
 * How long the instantaneous starvation condition must HOLD before it is
 * reported ([sustainedPlatformStarvation]). On a healthy fresh install the
 * masternode list lands within a couple of minutes of the filter scan
 * catching up, so five sustained minutes without one — while the L1 chain
 * is fully synced — is a real outage, not startup latency. (The observed
 * stranded wallet sat listless for hours.)
 */
internal const val PLATFORM_MNLIST_STARVATION_GRACE_MS = 5 * 60_000L

/**
 * Debounce the instantaneous starvation condition into the REPORTED one:
 * true only once [starved] has held for [graceMs], false the instant it
 * stops holding. The leading `emit(false)` keeps downstream `combine`s
 * flowing during the grace window; [distinctUntilChanged] on the input
 * keeps upstream re-emissions of the same verdict from restarting the
 * clock (the exact self-clearing bug this exists to fix — see
 * [mergeL1SyncUiStatus]).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun sustainedPlatformStarvation(
    starved: Flow<Boolean>,
    graceMs: Long = PLATFORM_MNLIST_STARVATION_GRACE_MS
): Flow<Boolean> = starved
    .distinctUntilChanged()
    .flatMapLatest { isStarved ->
        if (isStarved) {
            flow {
                emit(false)
                delay(graceMs)
                emit(true)
            }
        } else {
            flowOf(false)
        }
    }
    .distinctUntilChanged()

/**
 * Neutral chain-sync stage for the DETAIL readout (Tools → Network
 * Monitor) — finer-grained than [org.dash.wallet.common.data.SyncStage]
 * (which collapses the filter pipeline for the home header), with NO
 * indication of which engine produced it.
 */
enum class L1SyncStage { IDLE, CONNECTING, HEADERS, FILTER_HEADERS, MASTERNODE_LIST, FILTERS, SYNCED, ERROR }

/**
 * The detailed engine-agnostic L1 sync readout for the Network Monitor:
 * everything [L1SyncUiStatus] deliberately hides because ordinary screens
 * don't need it. Heights are 0 when the producing engine has no knowledge
 * of them (the UI renders those as unknown, never as "height 0").
 *
 * @property headerHeight/@property headerTarget header-chain scan position
 *   (target 0 = unknown/not applicable — e.g. the dashj regime reports no
 *   target).
 * @property filterHeight/@property filterTarget wallet-relevant
 *   compact-filter scan position (both 0 in the dashj regime, which has no
 *   filter pipeline).
 * @property mnListHeight masternode-list sync height.
 * @property chainLockHeight best PROVEN chainlocked height — a monotonic
 *   LOWER BOUND on the network's chainlock tip (see
 *   [BlockchainState.chainlockHeight]'s note), never a live mirror.
 */
data class L1SyncDetail(
    val stage: L1SyncStage = L1SyncStage.IDLE,
    val percentage: Int = 0,
    val isSynced: Boolean = false,
    val headerHeight: Long = 0,
    val headerTarget: Long = 0,
    val filterHeight: Long = 0,
    val filterTarget: Long = 0,
    val mnListHeight: Long = 0,
    val chainLockHeight: Int = 0
)

/**
 * SDK scan phase → neutral detail stage. ERROR always shows (honesty
 * first); otherwise a caught-up scan reads SYNCED even though a live SPV
 * never latches the SDK's own SYNCED phase (see
 * [ShadowSyncProgress.scanCaughtUpToTip]) — keeping the stage label
 * consistent with [sdkL1ScanCaughtUp]/[shadowSyncPercent], which report
 * synced/100% on the same predicate. Pure — host-testable.
 */
internal fun toL1SyncStage(progress: ShadowSyncProgress): L1SyncStage = when {
    progress.phase == ShadowSyncPhase.ERROR -> L1SyncStage.ERROR
    sdkL1ScanCaughtUp(progress) -> L1SyncStage.SYNCED
    else -> when (progress.phase) {
        ShadowSyncPhase.IDLE -> L1SyncStage.IDLE
        ShadowSyncPhase.CONNECTING -> L1SyncStage.CONNECTING
        ShadowSyncPhase.HEADERS -> L1SyncStage.HEADERS
        ShadowSyncPhase.FILTER_HEADERS -> L1SyncStage.FILTER_HEADERS
        ShadowSyncPhase.MASTERNODES -> L1SyncStage.MASTERNODE_LIST
        ShadowSyncPhase.FILTERS -> L1SyncStage.FILTERS
        // ERROR and the caught-up cases are handled above; SYNCED without
        // scanCaughtUpToTip still means synced (the SDK latched it itself).
        else -> L1SyncStage.SYNCED
    }
}

/**
 * Merge the two engines' feeds into the one detail readout. Pure —
 * host-testable.
 *
 * SDK regime: the live progress feed is the source, but the PERSISTED row
 * backstops it — after a process restart the engine cycles through
 * IDLE/CONNECTING for minutes with all-zero heights, and the row (written
 * by `SdkBlockchainStateService` from this same engine, so engine-correct)
 * still carries the last known header/mnlist/chainlock heights and
 * percent. `max()` keeps the readout monotonic through that window instead
 * of collapsing to zeros (the same don't-regress-on-unknown rule
 * `deriveBlockchainStateUpdate` applies when writing). The session
 * chainlock feed and the row are merged the same way — the row survives
 * restarts, the session feed leads within one.
 *
 * dashj regime: the row is all dashj has (no filter pipeline, no header
 * target), so filter fields stay 0/unknown.
 */
internal fun mergeL1SyncDetail(
    sdkOwnsL1: Boolean,
    progress: ShadowSyncProgress,
    sessionChainLockHeight: Int,
    state: BlockchainState?
): L1SyncDetail = if (sdkOwnsL1) {
    val idleOrConnecting = progress.phase == ShadowSyncPhase.IDLE ||
        progress.phase == ShadowSyncPhase.CONNECTING
    L1SyncDetail(
        stage = toL1SyncStage(progress),
        // Restart sawtooth guard: IDLE/CONNECTING carry no scan position,
        // so fall back to the row's (SDK-written, preserved) percent
        // rather than reporting 0 over a previously-synced chain.
        percentage = if (idleOrConnecting) {
            state?.percentageSync ?: 0
        } else {
            shadowSyncPercent(progress)
        },
        isSynced = sdkL1ScanCaughtUp(progress),
        headerHeight = maxOf(progress.headerHeight, (state?.bestChainHeight ?: 0).toLong()),
        headerTarget = progress.headerTarget,
        filterHeight = progress.filterHeight,
        filterTarget = progress.filterTarget,
        mnListHeight = maxOf(progress.mnListHeight, (state?.mnlistHeight ?: 0).toLong()),
        chainLockHeight = maxOf(sessionChainLockHeight, state?.chainlockHeight ?: 0)
    )
} else {
    L1SyncDetail(
        stage = when {
            state == null -> L1SyncStage.IDLE
            state.syncFailed() -> L1SyncStage.ERROR
            state.isSynced() -> L1SyncStage.SYNCED
            // dashj's row doesn't distinguish its internal stages here;
            // an in-progress sync reads as the block/header download.
            else -> L1SyncStage.HEADERS
        },
        percentage = dashjSyncPercentage(state),
        isSynced = state?.isSynced() == true,
        headerHeight = (state?.bestChainHeight ?: 0).toLong(),
        mnListHeight = (state?.mnlistHeight ?: 0).toLong(),
        chainLockHeight = state?.chainlockHeight ?: 0
    )
}

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
    blockchainStateProvider: BlockchainStateProvider,
    scope: CoroutineScope
) {
    /**
     * [sdkL1ScanCaughtUp] over the live SDK progress feed — published so
     * `CutoverUiDataService`'s balance hold and the home header's blinking
     * label share ONE source (see the function KDoc).
     */
    val sdkScanCaughtUp: Flow<Boolean> =
        l1ShadowSyncService.progress.map(::sdkL1ScanCaughtUp).distinctUntilChanged()

    /**
     * The SUSTAINED platform-outage verdict ([platformMasternodeListStarved]
     * held past [PLATFORM_MNLIST_STARVATION_GRACE_MS]): an L1-synced wallet
     * that has never obtained a masternode list has no DAPI reachability at
     * all — a failure the L1-derived impediment cannot see (and, post-sync,
     * actively clears within a block interval; see [mergeL1SyncUiStatus]).
     *
     * Held EAGERLY in the service's own long-lived [scope], NOT per
     * subscription: [status]'s downstream is screen-scoped
     * (`MainViewModel.syncStatus` uses `WhileSubscribed`), and a grace clock
     * that restarted on every navigation back to the home screen would need
     * the user to sit still for the whole grace before the outage ever
     * surfaced.
     */
    private val platformStarvedSustained: StateFlow<Boolean> = sustainedPlatformStarvation(
        combine(
            cutoverCoordinator.sdkOwnsL1Flow(),
            l1ShadowSyncService.progress,
            blockchainStateProvider.observeState()
        ) { sdkOwnsL1, progress, state ->
            platformMasternodeListStarved(sdkOwnsL1, progress, state)
        }
    ).catch { e ->
        // Fail-open to the pre-existing (L1-impediment-only) behavior — an
        // eager collector on the app scope must never take the process down.
        org.slf4j.LoggerFactory.getLogger(L1SyncStatusService::class.java)
            .warn("platform-starvation feed failed; banner falls back to the L1 impediment", e)
        emit(false)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The engine-agnostic L1 sync status every sync-aware screen renders. */
    val status: Flow<L1SyncUiStatus> =
        combine(
            cutoverCoordinator.sdkOwnsL1Flow(),
            l1ShadowSyncService.progress,
            blockchainStateProvider.observeState(),
            platformStarvedSustained
        ) { sdkOwnsL1, progress, state, platformStarved ->
            mergeL1SyncUiStatus(sdkOwnsL1, progress, state, platformStarved)
        }.distinctUntilChanged()

    /**
     * The engine-agnostic DETAIL readout for the Network Monitor
     * ([mergeL1SyncDetail]) — same seam discipline as [status]: the engine
     * choice is made here, reactively, and nothing above can observe it.
     */
    val details: Flow<L1SyncDetail> =
        combine(
            cutoverCoordinator.sdkOwnsL1Flow(),
            l1ShadowSyncService.progress,
            l1ShadowSyncService.chainLockHeight,
            blockchainStateProvider.observeState()
        ) { sdkOwnsL1, progress, chainLockHeight, state ->
            mergeL1SyncDetail(sdkOwnsL1, progress, chainLockHeight, state)
        }.distinctUntilChanged()
}
