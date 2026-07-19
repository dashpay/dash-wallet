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

import org.dash.wallet.common.data.SyncStage

// ── Kill-list Step B (sync-state track): SDK progress → BlockchainState ─
//
// Post-cutover the dashj engine is held, so nothing calls
// [de.schildbach.wallet.service.BlockchainStateDataProvider.updateBlockchainState]
// and the app-wide Room `blockchain_state` row freezes at the cutover
// snapshot — every sync indicator observing BlockchainState (home header
// via MainViewModel, More screen, sync pills, the ongoing sync
// notification, shielded chain-tip freshness) goes stale. The pure
// functions here derive the SAME row from the Kotlin SDK's SPV progress
// feed instead; [SdkBlockchainStateService] runs the equality-gated poll
// that applies them. Everything in this file is host-JVM unit-testable.

/**
 * One polled snapshot of the SDK-side chain knowledge — the equality-gate
 * unit: [SdkBlockchainStateService] only propagates a derivation when the
 * whole snapshot changed (the iOS-validated pattern; naive 1 Hz polling
 * burned CPU re-emitting identical progress, a documented iOS bug).
 *
 * @property progress the mirrored SPV progress ([L1ShadowSyncService.progress]).
 * @property tipUnixSeconds the SPV tip block's timestamp (the SDK's
 *   `spvTipUnixSecondsFlow`), 0 while unknown — dashj's
 *   `chainHead.header.time` analogue.
 * @property stalled the derived progress-stall verdict ([isSpvProgressStalled]).
 */
internal data class SdkChainSnapshot(
    val progress: ShadowSyncProgress,
    val tipUnixSeconds: Long,
    val stalled: Boolean
)

/**
 * What one propagated snapshot should write into the persisted
 * [org.dash.wallet.common.data.entity.BlockchainState] row (applied by
 * [de.schildbach.wallet.service.BlockchainStateDataProvider.updateSdkBlockchainState]
 * on its serial scope). Null fields mean PRESERVE the row's current value
 * — the SDK cannot claim knowledge it does not have (e.g. zero heights
 * while still connecting must not regress a previously-synced row).
 */
internal data class SdkBlockchainStateUpdate(
    /** SDK header-chain tip height, or null (no header knowledge yet — preserve). */
    val bestChainHeight: Int?,
    /**
     * Epoch-millis of the chain "date" freshness signal, or null (unknown —
     * preserve). NOT simply the header-tip time: while the scan is
     * incomplete this is the estimated timestamp of the FILTER-scan
     * position, so date-freshness keeps implying sync-completeness (see
     * [deriveBlockchainStateUpdate]).
     */
    val bestChainDateMs: Long?,
    /**
     * Combined headers+filters percent — same math as [shadowSyncPercent] —
     * or null (preserve) on a transient ERROR snapshot, so a peer hiccup
     * never regresses a previously-reported percent (dashj never did).
     */
    val percentageSync: Int?,
    /** Masternode-list sync height, or null (unknown — preserve). */
    val mnListHeight: Int?,
    /** Neutral sync stage for [org.dash.wallet.common.services.BlockchainStateProvider.observeSyncStage]. */
    val syncStage: SyncStage,
    /**
     * Whether a NETWORK impediment should be derived: the SDK has no
     * dashj-style impediment events, so a stalled/errored scan is the
     * closest honest signal that sync is not making progress (drives
     * [org.dash.wallet.common.data.entity.BlockchainState.syncFailed]).
     */
    val networkStalled: Boolean
)

/**
 * Same 2.625-minute effective DGW block target as
 * [de.schildbach.wallet.service.BlockchainStateDataProvider.BLOCK_TARGET_SPACING],
 * in millis — the tip-date estimator's fallback granularity.
 */
internal const val SDK_BLOCK_TARGET_SPACING_MS: Long = (2.625 * 60 * 1000).toLong()

/**
 * How long the SPV progress snapshot may sit unchanged mid-scan before the
 * derivation reports a NETWORK impediment ([SdkBlockchainStateUpdate.networkStalled]).
 * Generous vs the Rust client's own retry cadence so a slow peer handoff
 * doesn't flap the home header into "sync failed".
 */
internal const val SPV_STALL_THRESHOLD_MS: Long = 90_000L

/**
 * The progress-stall verdict feeding the derived NETWORK impediment:
 * - [ShadowSyncPhase.ERROR] is stalled immediately (the engine said so);
 * - active phases (CONNECTING through FILTERS) are stalled once the
 *   snapshot has not changed for [thresholdMs];
 * - IDLE and SYNCED are never stalled (nothing is supposed to move).
 * Pure — host-testable.
 */
internal fun isSpvProgressStalled(
    phase: ShadowSyncPhase,
    msSinceProgressChange: Long,
    thresholdMs: Long = SPV_STALL_THRESHOLD_MS
): Boolean = when (phase) {
    ShadowSyncPhase.ERROR -> true
    ShadowSyncPhase.IDLE, ShadowSyncPhase.SYNCED -> false
    ShadowSyncPhase.CONNECTING,
    ShadowSyncPhase.HEADERS,
    ShadowSyncPhase.FILTER_HEADERS,
    ShadowSyncPhase.MASTERNODES,
    ShadowSyncPhase.FILTERS -> msSinceProgressChange >= thresholdMs
}

/**
 * Rust SPV phase → the neutral [SyncStage] the pre-cutover pipeline maps
 * dashj's `PeerGroup.SyncStage` onto (see
 * [de.schildbach.wallet.service.BlockchainStateDataProvider]'s `toNeutral`):
 * not-connected states read OFFLINE (dashj's convention before the
 * peergroup is up), the compact-filter scan is the SDK's analogue of the
 * block download (BLOCKS), and there is no PREBLOCKS analogue (that stage
 * is dashj's Platform pre-block download).
 */
internal fun sdkSyncStage(phase: ShadowSyncPhase): SyncStage = when (phase) {
    ShadowSyncPhase.IDLE, ShadowSyncPhase.CONNECTING, ShadowSyncPhase.ERROR -> SyncStage.OFFLINE
    ShadowSyncPhase.HEADERS, ShadowSyncPhase.FILTER_HEADERS -> SyncStage.HEADERS
    ShadowSyncPhase.MASTERNODES -> SyncStage.MNLIST
    ShadowSyncPhase.FILTERS -> SyncStage.BLOCKS
    ShadowSyncPhase.SYNCED -> SyncStage.COMPLETE
}

/**
 * The full field derivation — one snapshot to one row update. Field notes:
 * - bestChainHeight: the SDK header tip ([ShadowSyncProgress.headerHeight]),
 *   dashj's `chainHead.height` analogue; preserved while 0 (no knowledge).
 * - bestChainDate: FRESHNESS MUST IMPLY COMPLETENESS. Under dashj the
 *   chain-head date only became fresh at the END of the block download, and
 *   consumers rely on that: `ShieldedTransferViewModel.isChainSyncedForTransfer`
 *   gates transfers on `now − bestChainDate < 1 h`, and
 *   `BlockchainServiceImpl` reads `bestChainDate < now − 1 h` as "still
 *   syncing". The SDK's header chain finishes long before the
 *   wallet-relevant compact-FILTER scan, so the header-tip time must NOT be
 *   reported mid-scan. Instead:
 *   - phase SYNCED → the real tip timestamp (or the header-gap estimate
 *     `now − headerGap × 2.625 min` while the tip feed lags) — fresh;
 *   - any other phase → the estimated timestamp of the current FILTER-scan
 *     position, `tipTime − (filterTarget − filterHeight) × 2.625 min`
 *     clamped ≤ tip time — honestly stale until the scan catches up;
 *   - no tip knowledge, or mid-scan with no filter target yet → null
 *     (preserve — the frozen row value must not be overwritten with a
 *     possibly-fresh guess).
 * - percentageSync: [shadowSyncPercent] — the combined headers+filters
 *   metric the home header already uses (the SDK's own `overallPercentage`
 *   under-reports during the filter scan). On a transient ERROR snapshot it
 *   is null (preserve): dashj never regressed the percent on a peer error,
 *   and an unconditional 0 would flap every `isSynced()` consumer
 *   100 → 0 → 100 (the impediment/stage carries the error instead).
 * - replaying: always false — the SDK has no replay concept; its full
 *   re-scan simply reads as percentageSync < 100 (the writer clears the
 *   row's flag).
 * - syncStage / networkStalled: [sdkSyncStage] / [SdkChainSnapshot.stalled].
 * Pure — host-testable.
 */
internal fun deriveBlockchainStateUpdate(
    snapshot: SdkChainSnapshot,
    nowMs: Long
): SdkBlockchainStateUpdate {
    val p = snapshot.progress
    val bestChainHeight = p.headerHeight.takeIf { it > 0 }?.toInt()
    // The network tip's timestamp: the SDK feed when present, else the
    // header-gap estimate (good to one block target).
    val tipTimeMs = when {
        snapshot.tipUnixSeconds > 0 -> snapshot.tipUnixSeconds * 1000
        p.headerHeight > 0 && p.headerTarget > 0 ->
            nowMs - (p.headerTarget - p.headerHeight).coerceAtLeast(0) * SDK_BLOCK_TARGET_SPACING_MS
        else -> null
    }
    val bestChainDateMs = when {
        tipTimeMs == null -> null
        p.phase == ShadowSyncPhase.SYNCED -> tipTimeMs
        // Mid-scan: report the FILTER-scan position's timestamp so the date
        // stays stale until the wallet-relevant scan completes (see KDoc).
        p.filterTarget > 0 ->
            (tipTimeMs - (p.filterTarget - p.filterHeight).coerceAtLeast(0) * SDK_BLOCK_TARGET_SPACING_MS)
                .coerceAtMost(tipTimeMs)
        else -> null
    }
    return SdkBlockchainStateUpdate(
        bestChainHeight = bestChainHeight,
        bestChainDateMs = bestChainDateMs,
        percentageSync = if (p.phase == ShadowSyncPhase.ERROR) null else shadowSyncPercent(p),
        mnListHeight = p.mnListHeight.takeIf { it > 0 }?.toInt(),
        syncStage = sdkSyncStage(p.phase),
        networkStalled = snapshot.stalled
    )
}
