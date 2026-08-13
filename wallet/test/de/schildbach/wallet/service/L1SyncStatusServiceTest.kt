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

import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.EnumSet

/**
 * Host-JVM tests for the engine-agnostic sync seam ([L1SyncStatusService]'s
 * pure core).
 *
 * What this pins: the choice between the two engines' sync feeds now
 * happens ONCE, here, instead of six times in five Fragments — and the
 * "SDK scan caught up" predicate has ONE definition, shared by the home
 * header's blinking "Syncing balance" label and `CutoverUiDataService`'s
 * balance hold, which must flip in the same instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class L1SyncStatusServiceTest {

    private fun progress(
        phase: ShadowSyncPhase = ShadowSyncPhase.FILTERS,
        headerHeight: Long = 1_514_660,
        headerTarget: Long = 1_514_660,
        filterHeight: Long = 1_400_000,
        filterTarget: Long = 1_514_660,
        mnListHeight: Long = 0,
        walletSyncedHeight: Long = 0
    ) = ShadowSyncProgress(
        phase, 0.0, headerHeight, headerTarget, filterHeight, filterTarget, mnListHeight,
        walletSyncedHeight
    )

    private fun dashjState(
        percentageSync: Int,
        replaying: Boolean = false,
        impediments: Set<Impediment> = emptySet(),
        bestChainHeight: Int = 1_500_000,
        chainlockHeight: Int = 0,
        mnlistHeight: Int = 0
    ) = BlockchainState(
        Date(1_000_000_000L),
        bestChainHeight,
        replaying,
        if (impediments.isEmpty()) {
            EnumSet.noneOf(Impediment::class.java)
        } else {
            EnumSet.copyOf(impediments)
        },
        chainlockHeight,
        mnlistHeight,
        percentageSync
    )

    // ── The one "SDK scan caught up" predicate ────────────────────────

    @Test
    fun sdkCaughtUp_acceptsTheLiveTipChase_notJustTheLatchedSyncedPhase() {
        // The SDK only latches SYNCED when its own overall state does, which a
        // live SPV chasing the moving tip essentially never reaches. Caught-up
        // is defined on the scan position instead.
        assertTrue(
            sdkL1ScanCaughtUp(progress(phase = ShadowSyncPhase.FILTERS, filterHeight = 1_514_659))
        )
        assertTrue(sdkL1ScanCaughtUp(progress(phase = ShadowSyncPhase.SYNCED)))
        assertFalse("a genuine mid-scan is not caught up", sdkL1ScanCaughtUp(progress()))
    }

    @Test
    fun sdkCaughtUp_blocksWhileTheBlockPipelineProvablyLags() {
        // The field incident: filters sub-progress at the tip (position-wise
        // "synced") one minute into a three-hour replay while the engine was
        // still downloading/processing the matched blocks — the committed
        // wallet cursor trailing the header tip is the only Kotlin-visible
        // evidence of that churn, and it must hold the gate closed.
        assertFalse(
            "filters at tip but the committed cursor far behind = still churning",
            sdkL1ScanCaughtUp(
                progress(filterHeight = 1_514_659, walletSyncedHeight = 1_200_000)
            )
        )
        // The SDK's own latched SYNCED phase is gated the same way — it was
        // observed holding SYNCED right through an armed replay.
        assertFalse(
            sdkL1ScanCaughtUp(
                progress(phase = ShadowSyncPhase.SYNCED, walletSyncedHeight = 1_200_000)
            )
        )
        // A cursor within tolerance of the tip is drained: caught up.
        assertTrue(
            sdkL1ScanCaughtUp(
                progress(filterHeight = 1_514_659, walletSyncedHeight = 1_514_658)
            )
        )
        // An UNKNOWN cursor (0 — no event/seed evidence) must never deadlock
        // the gate: pre-change behavior applies.
        assertTrue(
            sdkL1ScanCaughtUp(progress(filterHeight = 1_514_659, walletSyncedHeight = 0))
        )
    }

    // ── dashj's own percentage rule ───────────────────────────────────

    @Test
    fun dashjPercentage_showsAReplayAt100AsZero() {
        assertEquals(73, dashjSyncPercentage(dashjState(percentageSync = 73)))
        assertEquals(0, dashjSyncPercentage(dashjState(percentageSync = 100, replaying = true)))
        assertEquals(100, dashjSyncPercentage(dashjState(percentageSync = 100)))
        assertEquals(0, dashjSyncPercentage(null))
    }

    // ── The merge (what the Fragments used to do, six times) ──────────

    @Test
    fun merge_preCutover_readsDashj_andIgnoresTheSdkScan() {
        // Byte-for-byte the pre-cutover rendering: dashj's row decides, and a
        // caught-up SDK shadow running alongside must not clear the header.
        val status = mergeL1SyncUiStatus(
            sdkOwnsL1 = false,
            sdkProgress = progress(phase = ShadowSyncPhase.SYNCED),
            dashjState = dashjState(percentageSync = 42)
        )
        assertFalse(status.isSynced)
        assertEquals(42, status.percentage)
        assertFalse(status.isFailed)
    }

    @Test
    fun merge_postCutover_readsTheSdkScan_andIgnoresTheFrozenDashjPercent() {
        // THE REGRESSION the seam removes: post-cutover dashj's row percent is
        // whatever the held engine last wrote. Only the SDK scan is live.
        val status = mergeL1SyncUiStatus(
            sdkOwnsL1 = true,
            sdkProgress = progress(phase = ShadowSyncPhase.SYNCED),
            dashjState = dashjState(percentageSync = 42)
        )
        assertTrue(status.isSynced)
        assertEquals(100, status.percentage)
    }

    @Test
    fun merge_failureComesFromThePersistedRowInBothRegimes() {
        // Post-cutover SdkBlockchainStateService writes the SDK's own
        // progress-stall NETWORK impediment into the SAME row, so this signal
        // is engine-correct either way — which is why it is no longer read
        // from a dashj-only field alongside a branched sync feed.
        val impeded = dashjState(percentageSync = 50, impediments = setOf(Impediment.NETWORK))
        assertTrue(mergeL1SyncUiStatus(false, progress(), impeded).isFailed)
        assertTrue(mergeL1SyncUiStatus(true, progress(), impeded).isFailed)
        assertFalse(mergeL1SyncUiStatus(true, progress(), dashjState(percentageSync = 50)).isFailed)
    }

    @Test
    fun merge_missingRowIsNeverSyncedAndNeverFailed() {
        val status = mergeL1SyncUiStatus(sdkOwnsL1 = false, sdkProgress = progress(), dashjState = null)
        assertFalse(status.isSynced)
        assertEquals(0, status.percentage)
        assertFalse(status.isFailed)
    }

    // ── The detail merge (Tools → Network Monitor) ────────────────────

    @Test
    fun detail_sdkRegime_readsTheLiveScanPositions() {
        val detail = mergeL1SyncDetail(
            sdkOwnsL1 = true,
            progress = progress(phase = ShadowSyncPhase.FILTERS),
            sessionChainLockHeight = 0,
            state = null
        )
        assertEquals(L1SyncStage.FILTERS, detail.stage)
        assertEquals(1_514_660L, detail.headerHeight)
        assertEquals(1_514_660L, detail.headerTarget)
        assertEquals(1_400_000L, detail.filterHeight)
        assertEquals(1_514_660L, detail.filterTarget)
        assertFalse(detail.isSynced)
        // Mid-scan the combined percent is capped below 100.
        assertTrue(detail.percentage in 1..99)
    }

    @Test
    fun detail_sdkRegime_caughtUpScanReadsSyncedLikeTheHeaderDoes() {
        // Stage label, isSynced, and percentage must agree on the SAME
        // caught-up predicate the home header uses (sdkL1ScanCaughtUp).
        val detail = mergeL1SyncDetail(
            sdkOwnsL1 = true,
            progress = progress(phase = ShadowSyncPhase.FILTERS, filterHeight = 1_514_659),
            sessionChainLockHeight = 0,
            state = null
        )
        assertEquals(L1SyncStage.SYNCED, detail.stage)
        assertTrue(detail.isSynced)
        assertEquals(100, detail.percentage)
    }

    @Test
    fun detail_sdkRegime_errorPhaseShowsError_evenWithCaughtUpHeights() {
        val detail = mergeL1SyncDetail(
            sdkOwnsL1 = true,
            progress = progress(phase = ShadowSyncPhase.ERROR, filterHeight = 1_514_660),
            sessionChainLockHeight = 0,
            state = null
        )
        assertEquals(L1SyncStage.ERROR, detail.stage)
    }

    @Test
    fun detail_sdkRegime_restartWindowFallsBackToThePersistedRow() {
        // After a process restart the engine sits in IDLE/CONNECTING with
        // all-zero heights for minutes; the SDK-written row still carries
        // the last known chain knowledge, so the readout must not collapse
        // to zeros (the restart sawtooth).
        val row = dashjState(
            percentageSync = 100,
            bestChainHeight = 1_514_000,
            chainlockHeight = 1_513_990,
            mnlistHeight = 1_513_900
        )
        val detail = mergeL1SyncDetail(
            sdkOwnsL1 = true,
            progress = ShadowSyncProgress.IDLE.copy(phase = ShadowSyncPhase.CONNECTING),
            sessionChainLockHeight = 0,
            state = row
        )
        assertEquals(L1SyncStage.CONNECTING, detail.stage)
        assertEquals(100, detail.percentage)
        assertEquals(1_514_000L, detail.headerHeight)
        assertEquals(1_513_990, detail.chainLockHeight)
        assertEquals(1_513_900L, detail.mnListHeight)
    }

    @Test
    fun detail_sdkRegime_chainLockIsTheMaxOfSessionFeedAndRow() {
        fun detailWith(session: Int, row: Int) = mergeL1SyncDetail(
            sdkOwnsL1 = true,
            progress = progress(),
            sessionChainLockHeight = session,
            state = dashjState(percentageSync = 100, chainlockHeight = row)
        ).chainLockHeight
        // The row survives restarts; the session feed leads within one.
        assertEquals(1_514_500, detailWith(session = 1_514_500, row = 1_514_400))
        assertEquals(1_514_400, detailWith(session = 0, row = 1_514_400))
    }

    @Test
    fun detail_dashjRegime_readsTheRowOnly_withNoFilterPipeline() {
        val detail = mergeL1SyncDetail(
            sdkOwnsL1 = false,
            // A caught-up SDK shadow running alongside must not leak in.
            progress = progress(phase = ShadowSyncPhase.SYNCED),
            sessionChainLockHeight = 999_999,
            state = dashjState(
                percentageSync = 42,
                bestChainHeight = 1_100_000,
                chainlockHeight = 1_099_000,
                mnlistHeight = 1_098_000
            )
        )
        assertEquals(L1SyncStage.HEADERS, detail.stage)
        assertEquals(42, detail.percentage)
        assertFalse(detail.isSynced)
        assertEquals(1_100_000L, detail.headerHeight)
        assertEquals(0L, detail.headerTarget)
        assertEquals(0L, detail.filterHeight)
        assertEquals(0L, detail.filterTarget)
        assertEquals(1_099_000, detail.chainLockHeight)
        assertEquals(1_098_000L, detail.mnListHeight)
    }

    @Test
    fun detail_dashjRegime_stageFollowsTheRowVerdicts() {
        fun stageFor(state: BlockchainState?) = mergeL1SyncDetail(
            sdkOwnsL1 = false,
            progress = progress(),
            sessionChainLockHeight = 0,
            state = state
        ).stage
        assertEquals(L1SyncStage.IDLE, stageFor(null))
        assertEquals(L1SyncStage.SYNCED, stageFor(dashjState(percentageSync = 100)))
        assertEquals(
            L1SyncStage.ERROR,
            stageFor(dashjState(percentageSync = 50, impediments = setOf(Impediment.NETWORK)))
        )
    }

    @Test
    fun merge_postCutoverSyncedPredicate_matchesTheBalanceHoldGateExactly() {
        // The lockstep requirement: the blinking "Syncing balance" label
        // (merge -> isSynced) and CutoverUiDataService's balance hold
        // (sdkScanCaughtUp) must flip on the SAME predicate. Both now call the
        // same function, so this holds by construction — asserted here so a
        // future edit that re-splits them fails loudly.
        for (p in listOf(
            progress(),
            progress(phase = ShadowSyncPhase.SYNCED),
            progress(filterHeight = 1_514_659),
            progress(phase = ShadowSyncPhase.HEADERS, headerHeight = 10, headerTarget = 1_000)
        )) {
            assertEquals(
                sdkL1ScanCaughtUp(p),
                mergeL1SyncUiStatus(sdkOwnsL1 = true, sdkProgress = p, dashjState = null).isSynced
            )
        }
    }

    // ── Platform (masternode-list) starvation — the outage the L1 signals
    //    cannot see (observed live: banner self-cleared while the masternode
    //    list was still empty and DAPI had made zero calls for 33 minutes) ──

    /** A caught-up scan: filters within tolerance of the header tip. */
    private fun caughtUp(mnListHeight: Long = 0) =
        progress(phase = ShadowSyncPhase.MASTERNODES, filterHeight = 1_514_659, mnListHeight = mnListHeight)

    @Test
    fun platformStarved_firesOnlyForAnL1SyncedSdkWalletWithNoMnListAnywhere() {
        // The incident shape: SDK regime, scan caught up (header shows
        // "synced"), masternode list never obtained — live feed AND row at 0.
        assertTrue(platformMasternodeListStarved(true, caughtUp(), dashjState(percentageSync = 100)))
        // A missing row is no masternode knowledge either.
        assertTrue(platformMasternodeListStarved(true, caughtUp(), null))

        // dashj regime: not this signal's business.
        assertFalse(platformMasternodeListStarved(false, caughtUp(), dashjState(percentageSync = 100)))
        // Mid-scan: the masternode list is legitimately still pending.
        assertFalse(platformMasternodeListStarved(true, progress(), dashjState(percentageSync = 50)))
        // A live masternode height clears it…
        assertFalse(
            platformMasternodeListStarved(
                true, caughtUp(mnListHeight = 1_514_000), dashjState(percentageSync = 100)
            )
        )
        // …and so does the persisted row's (warm start of a healthy wallet:
        // the live feed reads 0 for a while, but the row remembers).
        assertFalse(
            platformMasternodeListStarved(
                true, caughtUp(), dashjState(percentageSync = 100, mnlistHeight = 1_514_000)
            )
        )
    }

    @Test
    fun merge_platformStarvation_drivesTheBanner_regardlessOfACleanRow() {
        // The L1-derived impediment self-clears within a block interval of
        // each appearance post-sync (every new block resets the stall clock),
        // so the starvation verdict must be able to hold the banner up on its
        // own, over a row with NO impediment recorded.
        val clean = dashjState(percentageSync = 100)
        assertTrue(
            mergeL1SyncUiStatus(true, caughtUp(), clean, platformStarved = true).isFailed
        )
        // Default keeps every pre-existing call site byte-identical.
        assertFalse(mergeL1SyncUiStatus(true, caughtUp(), clean).isFailed)
    }

    @Test
    fun sustainedStarvation_startupBlipNeverFlashesTheBanner() = runTest {
        // The masternode list is legitimately absent for a while right after
        // the scan catches up — a condition shorter than the grace must never
        // surface.
        val starved = MutableSharedFlow<Boolean>()
        val seen = mutableListOf<Boolean>()
        val job = launch { sustainedPlatformStarvation(starved, graceMs = 1_000).collect { seen += it } }
        runCurrent()
        starved.emit(true)
        testScheduler.advanceTimeBy(999)
        runCurrent()
        starved.emit(false)
        testScheduler.advanceTimeBy(5_000)
        runCurrent()
        assertFalse(seen.contains(true))
        job.cancel()
    }

    @Test
    fun sustainedStarvation_reportsAfterTheGrace_andHoldsUntilRecovery() = runTest {
        val starved = MutableSharedFlow<Boolean>()
        val seen = mutableListOf<Boolean>()
        val job = launch { sustainedPlatformStarvation(starved, graceMs = 1_000).collect { seen += it } }
        runCurrent()
        starved.emit(true)
        testScheduler.advanceTimeBy(1_001)
        runCurrent()
        assertEquals(true, seen.last())

        // Upstream re-emissions of the SAME verdict (the progress feed ticks
        // every second) must not reset or clear anything — the banner holds
        // while the condition holds. This is the self-clearing bug's exact
        // regression guard.
        starved.emit(true)
        testScheduler.advanceTimeBy(10_000)
        runCurrent()
        assertEquals(true, seen.last())

        // Recovery (a masternode list finally lands) clears immediately.
        starved.emit(false)
        runCurrent()
        assertEquals(false, seen.last())
        job.cancel()
    }

    @Test
    fun sustainedStarvation_reEmissionDuringTheGraceDoesNotRestartTheClock() = runTest {
        val starved = MutableSharedFlow<Boolean>()
        val seen = mutableListOf<Boolean>()
        val job = launch { sustainedPlatformStarvation(starved, graceMs = 1_000).collect { seen += it } }
        runCurrent()
        starved.emit(true)
        testScheduler.advanceTimeBy(600)
        runCurrent()
        // The 1 Hz progress feed re-derives the same verdict mid-grace…
        starved.emit(true)
        testScheduler.advanceTimeBy(600)
        runCurrent()
        // …and the report still lands one grace after the FIRST observation.
        assertEquals(true, seen.last())
        job.cancel()
    }


    // ── the DashPay half of "finished syncing" (FIX G) ───────────────────

    /**
     * The user complaint this closes: 11.10.86 reported synced at 17:12:39
     * (L1Shadow phase=SYNCED 100.0%) while contact sync then ran until
     * 17:20:09, the DashPay receiving accounts registered at 17:20:31, and the
     * correct balance only appeared at 17:25:00.
     */
    @Test
    fun aCaughtUpChainWithAnUnsettledDashPaySideIsNotFullySynced() {
        val status = mergeL1SyncUiStatus(
            sdkOwnsL1 = true,
            sdkProgress = caughtUp(),
            dashjState = null,
            dashPaySynced = false
        )
        // The funds/feature gate is untouched — the chain really is caught up.
        assertTrue(status.isSynced)
        // …but the user is still told it is working.
        assertFalse(status.isFullySynced)
    }

    @Test
    fun bothHalvesSettled_readsFullySynced() {
        val status = mergeL1SyncUiStatus(
            sdkOwnsL1 = true,
            sdkProgress = caughtUp(),
            dashjState = null,
            dashPaySynced = true
        )
        assertTrue(status.isFullySynced)
    }

    /**
     * HARD REQUIREMENT: a wallet with no DashPay identity must never be held
     * in "syncing" by the DashPay term. `applicable` starts false, so the
     * terms are settled from the first read and stay settled.
     */
    @Test
    fun aWalletWithoutAnIdentityIsTriviallySettled() {
        assertTrue(DashPaySyncTerms().settled)
        val status = DashPaySyncStatus()
        assertTrue(status.terms.value.settled)
        // Even mid-contact-sync bookkeeping cannot un-settle it while DashPay
        // does not apply.
        status.contactSyncStarted()
        status.setAccountBuildsSettled(false)
        status.setBackfillSettled(false)
        assertTrue(status.terms.value.settled)
    }

    /** Every term is load-bearing once DashPay applies. */
    @Test
    fun eachDashPayTermCanHoldTheSignal() {
        val status = DashPaySyncStatus()
        status.setApplicable(true)
        // A pass has never completed yet.
        assertFalse(status.terms.value.settled)

        status.contactSyncFinished()
        assertTrue(status.terms.value.settled)

        status.setAccountBuildsSettled(false)
        assertFalse(status.terms.value.settled)
        status.setAccountBuildsSettled(true)

        status.setBackfillSettled(false)
        assertFalse(status.terms.value.settled)
        status.setBackfillSettled(true)
        assertTrue(status.terms.value.settled)

        // A new pass re-opens it, and its ENDING closes it again — including a
        // failed ending, so a broken Platform connection cannot pin it on.
        status.contactSyncStarted()
        assertFalse(status.terms.value.settled)
        status.contactSyncFinished()
        assertTrue(status.terms.value.settled)
    }

    /** Losing DashPay applicability releases the hold immediately. */
    @Test
    fun becomingInapplicableReleasesTheHold() {
        val status = DashPaySyncStatus()
        status.setApplicable(true)
        assertFalse(status.terms.value.settled)
        status.setApplicable(false)
        assertTrue(status.terms.value.settled)
    }
}
