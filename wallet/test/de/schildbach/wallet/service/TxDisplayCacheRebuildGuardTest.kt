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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the two display-cache rebuild decisions
 * ([dashjRebuildWouldEraseHistory] and [decideCacheRebuild]) — the pure cores
 * of [TxDisplayCacheService.forceRebuildTransactionCache] and its
 * sync-complete completeness check.
 *
 * Post-cutover the dashj wallet is HELD with zero transactions while the SDK
 * feeds both caches, which breaks the two paths in opposite ways: the rebuild
 * would erase every visible row (it re-populates from the empty dashj wallet),
 * and the completeness check could never fire (it measured SDK-fed caches
 * against that same empty wallet). The tester's live numbers are used
 * throughout so the regressions are recognisable.
 */
class TxDisplayCacheRebuildGuardTest {

    // ── The rebuild guard (fix 1) ─────────────────────────────────────

    @Test
    fun postCutoverRebuildOnAHeldDashjWalletIsRefused() {
        assertTrue(dashjRebuildWouldEraseHistory(cutoverCommitted = true, dashjTxCount = 0))
    }

    @Test
    fun preCutoverRebuildIsAllowedEvenWithAnEmptyWallet() {
        // Nothing to erase and nothing to rebuild — but the refusal must be
        // scoped to the cutover, not to emptiness, or a fresh pre-cutover
        // wallet would stop rebuilding as it syncs.
        assertFalse(dashjRebuildWouldEraseHistory(cutoverCommitted = false, dashjTxCount = 0))
    }

    @Test
    fun postCutoverRebuildIsAllowedWhileDashjStillHoldsTransactions() {
        // The window between the cutover commit and the dashj wallet being
        // emptied: dashj can still rebuild what it holds, so the refusal must
        // not be on the cutover flag alone.
        assertFalse(dashjRebuildWouldEraseHistory(cutoverCommitted = true, dashjTxCount = 28_291))
    }

    @Test
    fun preCutoverRebuildIsAllowedWithAPopulatedWallet() {
        assertFalse(dashjRebuildWouldEraseHistory(cutoverCommitted = false, dashjTxCount = 12))
    }

    // ── The completeness check (fix 2) ────────────────────────────────

    /** The tester's live post-cutover numbers, which the old check called complete. */
    private fun postCutover(
        sdkRecordCount: Int?,
        displayRowCount: Int
    ) = decideCacheRebuild(
        cutoverCommitted = true,
        sdkRecordCount = sdkRecordCount,
        walletTxCount = 0,
        cachedTxCount = 28_291,
        groupCount = 321,
        displayRowCount = displayRowCount
    )

    @Test
    fun postCutoverGapIsDetectedFromTheSdkRecordCount() {
        val decision = postCutover(sdkRecordCount = 5_100, displayRowCount = 4_810)
        assertEquals(CacheRebuildAction.SDK_RECONCILE, decision.action)
        assertTrue(decision.reason.contains("5100"))
        assertTrue(decision.reason.contains("4810"))
    }

    @Test
    fun postCutoverGapNeverAsksForTheDashjRebuildThatWouldEraseTheRows() {
        // The old check's remedy — forceRebuildTransactionCache — is exactly
        // what the guard above refuses, so a post-cutover gap must route to
        // the SDK's own reconcile and to nothing else, whatever the dashj-side
        // numbers say.
        for (walletTxCount in listOf(0, 5)) {
            for (groupCount in listOf(0, 321, 99_999)) {
                val decision = decideCacheRebuild(
                    cutoverCommitted = true,
                    sdkRecordCount = 5_100,
                    walletTxCount = walletTxCount,
                    cachedTxCount = 28_291,
                    groupCount = groupCount,
                    displayRowCount = 4_810
                )
                assertEquals(CacheRebuildAction.SDK_RECONCILE, decision.action)
            }
        }
    }

    @Test
    fun postCutoverCompleteCacheDoesNothing() {
        assertEquals(CacheRebuildAction.NONE, postCutover(4_810, 4_810).action)
    }

    @Test
    fun postCutoverCollapsedRowsAreNotReadAsAGap() {
        // Historical-mixing records collapse into per-day rows, so FEWER rows
        // than records is the only detectable gap; the reverse must stay quiet.
        assertEquals(CacheRebuildAction.NONE, postCutover(4_000, 4_810).action)
    }

    @Test
    fun postCutoverWithoutAnSdkCountJudgesNothing() {
        val decision = postCutover(sdkRecordCount = null, displayRowCount = 4_810)
        assertEquals(CacheRebuildAction.NONE, decision.action)
        assertTrue(decision.reason.contains("unavailable"))
    }

    @Test
    fun postCutoverFreshWalletIsQuiet() {
        assertEquals(
            CacheRebuildAction.NONE,
            decideCacheRebuild(
                cutoverCommitted = true,
                sdkRecordCount = 0,
                walletTxCount = 0,
                cachedTxCount = 0,
                groupCount = 0,
                displayRowCount = 0
            ).action
        )
    }

    @Test
    fun preCutoverMissingGroupCacheEntriesStillRebuildFromDashj() {
        val decision = decideCacheRebuild(
            cutoverCommitted = false,
            sdkRecordCount = null,
            walletTxCount = 500,
            cachedTxCount = 400,
            groupCount = 120,
            displayRowCount = 120
        )
        assertEquals(CacheRebuildAction.DASHJ_REBUILD, decision.action)
        assertTrue(decision.reason.contains("txsMissing=true"))
    }

    @Test
    fun preCutoverPartialDisplayCacheStillRebuildsFromDashj() {
        val decision = decideCacheRebuild(
            cutoverCommitted = false,
            sdkRecordCount = null,
            walletTxCount = 500,
            cachedTxCount = 500,
            groupCount = 120,
            displayRowCount = 90
        )
        assertEquals(CacheRebuildAction.DASHJ_REBUILD, decision.action)
        assertTrue(decision.reason.contains("displayIncomplete=true"))
    }

    @Test
    fun preCutoverCompleteCacheDoesNothing() {
        assertEquals(
            CacheRebuildAction.NONE,
            decideCacheRebuild(
                cutoverCommitted = false,
                sdkRecordCount = null,
                walletTxCount = 500,
                cachedTxCount = 500,
                groupCount = 120,
                displayRowCount = 120
            ).action
        )
    }

    @Test
    fun preCutoverFreshInstallDoesNothing() {
        assertEquals(
            CacheRebuildAction.NONE,
            decideCacheRebuild(
                cutoverCommitted = false,
                sdkRecordCount = null,
                walletTxCount = 0,
                cachedTxCount = 0,
                groupCount = 0,
                displayRowCount = 0
            ).action
        )
    }

    @Test
    fun preCutoverSdkCountIsIgnoredEvenIfSupplied() {
        // The non-cutover path is byte-for-byte the original rule set: a
        // stray SDK count must not change any of its verdicts.
        assertEquals(
            CacheRebuildAction.NONE,
            decideCacheRebuild(
                cutoverCommitted = false,
                sdkRecordCount = 99_999,
                walletTxCount = 500,
                cachedTxCount = 500,
                groupCount = 120,
                displayRowCount = 120
            ).action
        )
    }
}
