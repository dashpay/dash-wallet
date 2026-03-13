/*
 * Copyright 2024 Dash Core Group.
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

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.slf4j.LoggerFactory

/**
 * In-memory [PagingSource] over a pre-built list of [HistoryRowView] rows (transaction rows
 * with date-header entries already interleaved).
 *
 * Used for the initial home-screen display so that no [PagingData] transforms (e.g.
 * [insertSeparators]) are needed on the hot path.  All data is already in [items] so each
 * page load is just an array slice — no IO, no SQL, no transforms.
 *
 * This source is NOT auto-invalidated by Room's [InvalidationTracker].  It is used only until
 * [MainViewModel.rebuildWrappedList] completes and replaces it with the Room-backed pager.
 */
class PrebuiltRowsPagingSource(
    private val items: List<HistoryRowView>
) : PagingSource<Int, HistoryRowView>() {

    companion object {
        private val log = LoggerFactory.getLogger(PrebuiltRowsPagingSource::class.java)
    }

    override fun getRefreshKey(state: PagingState<Int, HistoryRowView>): Int? =
        state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, HistoryRowView> {
        val t0 = System.currentTimeMillis()
        val offset = params.key ?: 0
        val slice = items.drop(offset).take(params.loadSize)
        val result = LoadResult.Page(
            data = slice,
            prevKey = if (offset == 0) null else offset,
            nextKey = if (slice.size < params.loadSize) null else offset + slice.size
        )
        log.info("STARTUP PrebuiltRowsPagingSource.load offset={} size={} thread={} in {}ms",
            offset, slice.size, Thread.currentThread().name, System.currentTimeMillis() - t0)
        return result
    }
}