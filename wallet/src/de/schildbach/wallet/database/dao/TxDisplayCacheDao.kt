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
import kotlinx.coroutines.flow.Flow

@Dao
interface TxDisplayCacheDao {
    /**
     * Room-native PagingSource filtered by [filterFlag].
     * Pass [filterFlag] = 0 for ALL (no WHERE filtering).
     * Pass [TxDisplayCacheEntry.FLAG_SENT], [FLAG_RECEIVED], or [FLAG_GIFT_CARD] for filtered views.
     */
    @Query("SELECT * FROM tx_display_cache WHERE (:filterFlag = 0 OR (filterFlags & :filterFlag) != 0) ORDER BY time DESC")
    fun pagingSource(filterFlag: Int): PagingSource<Int, TxDisplayCacheEntry>

    @Query("SELECT COUNT(*) FROM tx_display_cache")
    suspend fun getCount(): Int

    /** Fetch all entries ordered newest-first — used for in-memory snapshot on startup. */
    @Query("SELECT * FROM tx_display_cache ORDER BY time DESC")
    suspend fun getAll(): List<TxDisplayCacheEntry>

    /** Insert or replace entries (used for full rebuild and targeted metadata updates). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TxDisplayCacheEntry>)

    /** Fetch specific entries by rowId — used to merge before a targeted upsert. */
    @Query("SELECT * FROM tx_display_cache WHERE rowId IN (:rowIds)")
    suspend fun getEntriesByIds(rowIds: List<String>): List<TxDisplayCacheEntry>

    /**
     * REACTIVE observation of every corrected display row attributed to one DashPay contact
     * (existing [TxDisplayCacheEntry.contactUserId] column — no schema change). Emits the current
     * rows immediately and re-emits whenever any of this contact's rows change (e.g. a just-sent
     * tx's direction/amount corrects, or its `statusText` flips "Sending"→"Sent" on IS-lock), so
     * the contact-detail payment rows update LIVE instead of being resolved once at list-build time.
     *
     * [CutoverUiDataService] populates `contactUserId` on exactly these contact send/receive rows
     * (contact-by-txid attribution), and that value is the same Base58 identity id the contact-detail
     * screen keys on — so filtering by it returns the same rows the one-shot txid lookup did.
     */
    @Query("SELECT * FROM tx_display_cache WHERE contactUserId = :userId ORDER BY time DESC")
    fun observeByContactUserId(userId: String): Flow<List<TxDisplayCacheEntry>>

    /**
     * One-shot fresh read of every corrected display row attributed to one DashPay contact.
     *
     * The reactive [observeByContactUserId] Flow relies on Room's InvalidationTracker to
     * re-emit, which on-device can miss a just-written flip (pending→confirmed) for minutes.
     * The contact-detail merge instead re-runs this suspend query on each
     * [de.schildbach.wallet.service.DisplayCacheRefreshBus] tick so it always sees the current
     * rows rather than a stale Flow snapshot.
     */
    @Query("SELECT * FROM tx_display_cache WHERE contactUserId = :userId ORDER BY time DESC")
    suspend fun getByContactUserId(userId: String): List<TxDisplayCacheEntry>

    /**
     * Synchronous (non-suspend) fetch of a single row's signed display value (satoshis,
     * negative = sent) by rowId (lowercase display-hex txid), or null when no cached row exists.
     *
     * Used only on the received-coins notification path, which runs on a bitcoinj wallet-listener
     * thread (never the main thread), where the SDK-corrected net is preferred over the dashj
     * misread. Callers MUST wrap this in a try/catch and fail-soft to the dashj value on any error.
     */
    @Query("SELECT valueSatoshis FROM tx_display_cache WHERE rowId = :rowId LIMIT 1")
    fun getValueSatoshisByIdSync(rowId: String): Long?

    @Query("DELETE FROM tx_display_cache")
    suspend fun deleteAll()

    /** Delete specific rows by rowId. */
    @Query("DELETE FROM tx_display_cache WHERE rowId IN (:rowIds)")
    suspend fun deleteByIds(rowIds: List<String>)

    /**
     * Atomically upsert group rows while removing the individual rows their member
     * transactions previously rendered as — used when historical CoinJoin mixing
     * transactions collapse into their per-day "Mixing" group row. One transaction so
     * the pager never observes the intermediate state (both the group row AND the
     * individual member rows, or neither).
     */
    @Transaction
    suspend fun upsertGroupRows(rows: List<TxDisplayCacheEntry>, removeRowIds: List<String>) {
        if (rows.isNotEmpty()) insertAll(rows)
        if (removeRowIds.isNotEmpty()) deleteByIds(removeRowIds)
    }

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