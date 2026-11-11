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

package de.schildbach.wallet.ui.main

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four cache→live handoff sequences raised on PR #1666's review
 * (https://github.com/dashpay/dash-wallet/pull/1666#discussion_r4434971508), now assertable
 * against [transactionsListPolicy] without a Fragment/Robolectric view harness.
 *
 * Each sequence threads [TransactionsListPolicy.swapToLive] back into `cacheAdapterAttached` for
 * the next call, exactly as `WalletTransactionsFragment` does when it applies the swap.
 */
class TransactionsListPolicyTest {
    private val loading = LoadState.Loading
    private val settled = LoadState.NotLoading(endOfPaginationReached = false)

    @Test
    fun `committed cached rows then an empty live result with an empty header swaps and shows the empty view`() {
        // Startup: cache committed some rows while the live Pager is still loading.
        val duringLoad = transactionsListPolicy(
            cacheAdapterAttached = true,
            cacheHasRows = true,
            refreshState = loading,
            transactionsLoaded = false,
            isBuildingCache = false,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = false, isLoading = false, isEmpty = false), duringLoad)

        // The live Pager settles on an empty answer (the filter matches nothing).
        val settledEmpty = transactionsListPolicy(
            cacheAdapterAttached = true,
            cacheHasRows = true,
            refreshState = settled,
            transactionsLoaded = true,
            isBuildingCache = false,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = true, isLoading = false, isEmpty = true), settledEmpty)
    }

    @Test
    fun `a late cachedRows emission after detach does not re-attach and the empty view stays`() {
        // The cache adapter is already detached from a prior settled-empty evaluation.
        val lateCacheCommit = transactionsListPolicy(
            cacheAdapterAttached = false,
            cacheHasRows = true, // the late emission has rows, but attachment excludes them
            refreshState = settled,
            transactionsLoaded = true,
            isBuildingCache = false,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = false, isLoading = false, isEmpty = true), lateCacheCommit)
    }

    @Test
    fun `view recreation after an empty result settled swaps immediately with no flash of stale cache rows`() {
        // onViewCreated resets cacheAdapterAttached/cacheHasRows for the new view, but
        // transactionsLoaded is service-wide and was already flipped by the previous view.
        val freshViewCatchUp = transactionsListPolicy(
            cacheAdapterAttached = true,
            cacheHasRows = false,
            refreshState = settled,
            transactionsLoaded = true,
            isBuildingCache = false,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = true, isLoading = false, isEmpty = true), freshViewCatchUp)
    }

    @Test
    fun `clearing the filter after an empty result brings the live rows back`() {
        // Already swapped away from the cache by the earlier empty result.
        val duringRefilter = transactionsListPolicy(
            cacheAdapterAttached = false,
            cacheHasRows = false,
            refreshState = loading,
            transactionsLoaded = true,
            isBuildingCache = false,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = false, isLoading = true, isEmpty = false), duringRefilter)

        val filterCleared = transactionsListPolicy(
            cacheAdapterAttached = false,
            cacheHasRows = false,
            refreshState = settled,
            transactionsLoaded = true,
            isBuildingCache = false,
            liveItemCount = 5,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = false, isLoading = false, isEmpty = false), filterCleared)
    }

    @Test
    fun `a non-empty header keeps the list showing even when the live side is empty`() {
        val emptyLiveNonEmptyHeader = transactionsListPolicy(
            cacheAdapterAttached = false,
            cacheHasRows = false,
            refreshState = settled,
            transactionsLoaded = true,
            isBuildingCache = false,
            liveItemCount = 0,
            headerIsEmpty = false
        )
        assertEquals(false, emptyLiveNonEmptyHeader.isEmpty)
    }

    @Test
    fun `rebuilding the cache from scratch shows loading only while nothing is on screen`() {
        val buildingFromScratch = transactionsListPolicy(
            cacheAdapterAttached = true,
            cacheHasRows = false,
            refreshState = settled,
            transactionsLoaded = false,
            isBuildingCache = true,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(true, buildingFromScratch.isLoading)

        val rebuildingWithCacheRowsVisible = transactionsListPolicy(
            cacheAdapterAttached = true,
            cacheHasRows = true,
            refreshState = settled,
            transactionsLoaded = false,
            isBuildingCache = true,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(false, rebuildingWithCacheRowsVisible.isLoading)
    }

    @Test
    fun `a later rebuild can request loading and empty handling together`() {
        // transactionsLoaded remains true across later rebuilds. showEmptyView()
        // separately suppresses the empty text while isBuildingCache is true.
        val laterRebuild = transactionsListPolicy(
            cacheAdapterAttached = true,
            cacheHasRows = false,
            refreshState = settled,
            transactionsLoaded = true,
            isBuildingCache = true,
            liveItemCount = 0,
            headerIsEmpty = true
        )
        assertEquals(TransactionsListPolicy(swapToLive = true, isLoading = true, isEmpty = true), laterRebuild)
    }
}
