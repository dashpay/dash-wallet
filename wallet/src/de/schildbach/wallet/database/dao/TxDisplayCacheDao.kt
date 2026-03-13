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

package de.schildbach.wallet.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry

@Dao
interface TxDisplayCacheDao {
    /**
     * Room-native PagingSource ordered newest-first.  Room's InvalidationTracker
     * automatically invalidates this source whenever any row in [tx_display_cache]
     * is inserted, updated, or deleted — no manual invalidate() calls needed for
     * data changes (only needed for in-memory changes like contact resolution).
     */
    @Query("SELECT * FROM tx_display_cache ORDER BY time DESC")
    fun pagingSource(): PagingSource<Int, TxDisplayCacheEntry>

    @Query("SELECT COUNT(*) FROM tx_display_cache")
    suspend fun getCount(): Int

    /** Insert or replace entries (used for full rebuild and targeted metadata updates). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TxDisplayCacheEntry>)

    /** Fetch specific entries by rowId — used to merge before a targeted upsert. */
    @Query("SELECT * FROM tx_display_cache WHERE rowId IN (:rowIds)")
    suspend fun getEntriesByIds(rowIds: List<String>): List<TxDisplayCacheEntry>

    @Query("DELETE FROM tx_display_cache")
    suspend fun deleteAll()

    /**
     * Atomically replace the entire cache: delete all existing rows and insert the new ones
     * in a single transaction.  This prevents Room's InvalidationTracker from firing between
     * delete and insert, which would cause the PagingSource to briefly see an empty table.
     */
    @Transaction
    suspend fun replaceAll(entries: List<TxDisplayCacheEntry>) {
        deleteAll()
        if (entries.isNotEmpty()) insertAll(entries)
    }
}