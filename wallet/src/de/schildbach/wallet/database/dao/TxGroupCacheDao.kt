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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.schildbach.wallet.database.entity.TxGroupCacheEntry

@Dao
interface TxGroupCacheDao {

    /**
     * Returns all rows ordered so that groups appear newest-first and txs within each
     * group appear in [sortOrder] (oldest tx first).  Callers should group the flat list
     * by [TxGroupCacheEntry.groupId] before processing — Kotlin's [groupBy] preserves
     * insertion order, so the groupDate DESC ordering is maintained.
     */
    @Query("SELECT * FROM tx_group_cache ORDER BY groupDate DESC, groupId, sortOrder ASC")
    suspend fun getAll(): List<TxGroupCacheEntry>

    /** Returns the number of distinct groups (not the total row count). */
    @Query("SELECT COUNT(DISTINCT groupId) FROM tx_group_cache")
    suspend fun getGroupCount(): Int

    /**
     * Returns only the "active" groups that may still receive new transactions:
     * - CoinJoin groups dated [today] (today's mixing sessions)
     * - All CrowdNode groups (signup flow may be in progress)
     *
     * Used by [MainViewModel.initializeFactoriesFromCache] to seed factory state
     * at startup without loading the entire group history.
     */
    @Query(
        "SELECT * FROM tx_group_cache " +
            "WHERE (wrapperType = 'coinjoin' AND groupDate = :today) " +
            "   OR wrapperType = 'crowdnode' " +
            "ORDER BY groupId, sortOrder ASC"
    )
    suspend fun getActiveGroups(today: String): List<TxGroupCacheEntry>

    /**
     * Batch lookup: returns group entries whose [TxGroupCacheEntry.txId] is in [txIds].
     * Used to lazily resolve known transactions that are not yet in the in-memory wrapper
     * list, without a separate round-trip per transaction.
     */
    @Query("SELECT * FROM tx_group_cache WHERE txId IN (:txIds)")
    suspend fun getGroupsForTxIds(txIds: List<String>): List<TxGroupCacheEntry>

    /**
     * Returns all entries for a single group in sort order — used for on-demand wrapper
     * reconstruction when a user taps a group row before the full list is loaded.
     */
    @Query("SELECT * FROM tx_group_cache WHERE groupId = :groupId ORDER BY sortOrder ASC")
    suspend fun getGroupEntries(groupId: String): List<TxGroupCacheEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TxGroupCacheEntry>)

    @Query("DELETE FROM tx_group_cache")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(entries: List<TxGroupCacheEntry>) {
        deleteAll()
        if (entries.isNotEmpty()) insertAll(entries)
    }
}