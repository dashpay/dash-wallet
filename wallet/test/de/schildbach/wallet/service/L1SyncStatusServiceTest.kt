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
class L1SyncStatusServiceTest {

    private fun progress(
        phase: ShadowSyncPhase = ShadowSyncPhase.FILTERS,
        headerHeight: Long = 1_514_660,
        headerTarget: Long = 1_514_660,
        filterHeight: Long = 1_400_000,
        filterTarget: Long = 1_514_660
    ) = ShadowSyncProgress(phase, 0.0, headerHeight, headerTarget, filterHeight, filterTarget)

    private fun dashjState(
        percentageSync: Int,
        replaying: Boolean = false,
        impediments: Set<Impediment> = emptySet()
    ) = BlockchainState(
        Date(1_000_000_000L),
        1_500_000,
        replaying,
        if (impediments.isEmpty()) {
            EnumSet.noneOf(Impediment::class.java)
        } else {
            EnumSet.copyOf(impediments)
        },
        0,
        0,
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
}
