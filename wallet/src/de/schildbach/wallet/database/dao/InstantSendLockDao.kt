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

package de.schildbach.wallet.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.schildbach.wallet.database.entity.InstantSendLockEntry

/**
 * App-owned persistence of engine-reported InstantSend locks — see
 * [InstantSendLockEntry] for why this table exists (the SDK mirror never
 * records the lock, so this is the only restart-safe IS-lock evidence).
 */
@Dao
interface InstantSendLockDao {
    /** IGNORE: a lock is a fact — re-observing it must not bump [InstantSendLockEntry.lockedAtMs]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: InstantSendLockEntry)

    /** The subset of [txIds] (display-hex) with a persisted IS-lock. Callers chunk to <999 ids. */
    @Query("SELECT txId FROM instant_send_locks WHERE txId IN (:txIds)")
    suspend fun getLockedTxIds(txIds: List<String>): List<String>

    /**
     * The most recently locked txids (display-hex), newest first, bounded —
     * the preflight folds these into its finality SQL as an IN-list, so the
     * read must stay comfortably under SQLite's 999-variable cap.
     */
    @Query("SELECT txId FROM instant_send_locks ORDER BY lockedAtMs DESC LIMIT :limit")
    suspend fun getMostRecentTxIds(limit: Int): List<String>

    /**
     * Retention prune: entries past the pre-block window are redundant (the
     * SDK mirror records finality itself once the block lands).
     */
    @Query("DELETE FROM instant_send_locks WHERE lockedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM instant_send_locks")
    suspend fun deleteAll()
}
