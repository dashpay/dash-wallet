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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the foreground service's idle detector
 * ([isSyncIdle]) and its SDK sample source ([sdkActivitySample]).
 *
 * The regression this pins: post-cutover the dashj peergroup is held, so
 * every counter the detector used to read was permanently 0 and the
 * service `stopSelf()`d roughly two minutes after EVERY start — in the
 * middle of the SDK's initial scan.
 */
class SyncActivityIdleDetectorTest {

    private fun progress(
        headerHeight: Long = 1_500_000,
        filterHeight: Long = 1_400_000,
        mnListHeight: Long = 1_499_000
    ) = ShadowSyncProgress(
        phase = ShadowSyncPhase.FILTERS,
        overallPercent = 50.0,
        headerHeight = headerHeight,
        headerTarget = 1_514_660,
        filterHeight = filterHeight,
        filterTarget = 1_514_660,
        mnListHeight = mnListHeight
    )

    private val quiet = SyncActivitySample(0, 0, 0, 0)

    // ── The idle RULE (unchanged semantics, now testable) ─────────────

    @Test
    fun idle_neverBeforeTheMinimumHistory() {
        assertFalse(isSyncIdle(emptyList()))
        assertFalse(isSyncIdle(listOf(quiet)))
        assertTrue(isSyncIdle(List(MIN_COLLECT_HISTORY) { quiet }))
    }

    @Test
    fun idle_anyCounterInsideItsWindowKeepsTheServiceAlive() {
        val withBlocks = listOf(SyncActivitySample(0, 1, 0, 0), quiet, quiet)
        assertFalse("a recent block download is activity", isSyncIdle(withBlocks))

        val withHeaders = listOf(quiet, SyncActivitySample(0, 0, 5, 0), quiet)
        assertFalse("a recent header batch is activity", isSyncIdle(withHeaders))

        val withMnList = listOf(quiet, quiet, SyncActivitySample(0, 0, 0, 1))
        assertFalse("a recent mnlistdiff is activity", isSyncIdle(withMnList))
    }

    @Test
    fun idle_transactionsHaveTheLongestWindow() {
        // A transaction at index 8 is still inside IDLE_TRANSACTION_TIMEOUT_MIN (9)…
        val recentTx = List(8) { quiet } + SyncActivitySample(1, 0, 0, 0)
        assertFalse(isSyncIdle(recentTx))
        // …while a BLOCK that far back is outside IDLE_BLOCK_TIMEOUT_MIN (2).
        val staleBlock = List(8) { quiet } + SyncActivitySample(0, 1, 0, 0)
        assertTrue(isSyncIdle(staleBlock))
    }

    // ── The SDK sample source (the actual fix) ────────────────────────

    @Test
    fun sdkSample_firstTickHasNoReferenceSnapshot() {
        assertEquals(
            "no previous snapshot must read as all-zero, not as a height-vs-0 delta",
            SyncActivitySample(0, 0, 0, 0),
            sdkActivitySample(previous = null, current = progress(), txEventsSinceLastSample = 0)
        )
    }

    @Test
    fun sdkSample_aScanningEngineIsNOTIdle() {
        // THE REGRESSION: this is what a mid-scan SDK engine looks like while
        // the dashj counters — which used to be the only input — read 0/0/0/0.
        val before = progress(headerHeight = 1_500_000, filterHeight = 1_400_000, mnListHeight = 1_499_000)
        val after = progress(headerHeight = 1_500_020, filterHeight = 1_402_000, mnListHeight = 1_499_010)

        val sample = sdkActivitySample(before, after, txEventsSinceLastSample = 2)
        assertEquals(SyncActivitySample(2, 2_000, 20, 10), sample)
        assertFalse(
            "a scanning SDK engine must keep the foreground service alive",
            isSyncIdle(listOf(sample, sample))
        )

        // …and this is what the OLD sample source reported for the very same
        // instant, because every one of its counters came from the HELD dashj
        // peergroup: two ticks of nothing, then stopSelf(). Same rule, wrong
        // inputs — which is why the fix changed the SOURCE, not the rule.
        val dashjCountersWithTheEngineHeld = listOf(quiet, quiet)
        assertTrue(isSyncIdle(dashjCountersWithTheEngineHeld))
    }

    @Test
    fun sdkSample_aGenuinelyIdleEngineStillStopsTheService() {
        // The detector must not simply be disabled: nothing moving is still idle.
        val stable = progress()
        val sample = sdkActivitySample(stable, stable, txEventsSinceLastSample = 0)
        assertEquals(SyncActivitySample(0, 0, 0, 0), sample)
        assertTrue(isSyncIdle(List(MIN_COLLECT_HISTORY) { sample }))
    }

    // ── The missing-blockstore wallet.reset() guard ───────────────────

    @Test
    fun walletReset_allowedPreCutover_soDashjCanResyncAsBefore() {
        assertTrue(
            "pre-cutover the peergroup DOES re-download — behaviour must be unchanged",
            mayResetDashjWalletForMissingBlockstore(sdkOwnsL1 = false, dashjTransactionCount = 42)
        )
    }

    @Test
    fun walletReset_refusedPostCutover_whenItWouldDestroyHistory() {
        // FIX-pin: post-cutover the peergroup is held, so the reset buys no
        // re-download — it just empties the wallet the home-screen history is
        // rebuilt from, and its reset listener deletes both display caches.
        assertFalse(
            mayResetDashjWalletForMissingBlockstore(sdkOwnsL1 = true, dashjTransactionCount = 42)
        )
    }

    @Test
    fun walletReset_stillAllowedPostCutover_whenThereIsNothingToLose() {
        // A wallet with no transactions (fresh install) loses nothing, so it
        // keeps the original path rather than adding a second behaviour.
        assertTrue(
            mayResetDashjWalletForMissingBlockstore(sdkOwnsL1 = true, dashjTransactionCount = 0)
        )
    }

    @Test
    fun sdkSample_clampsBackwardsHeightsToZero() {
        // A re-scan walks the filter height backwards; a negative "download
        // count" would be meaningless (and, being > 0 in neither direction,
        // must simply read as no progress).
        val before = progress(filterHeight = 1_400_000)
        val after = progress(filterHeight = 1_000_000)
        assertEquals(0, sdkActivitySample(before, after, 0).blocksDownloaded)
        assertEquals(0, sdkActivitySample(before, after, -5).transactionsReceived)
    }
}
