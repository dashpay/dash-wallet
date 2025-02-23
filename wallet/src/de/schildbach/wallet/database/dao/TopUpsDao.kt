/*
 * Copyright 2025 Dash Core Group.
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
import de.schildbach.wallet.database.entity.TopUp
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash

@Dao
interface TopUpsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(topUp: TopUp)

    @Query("SELECT * FROM topup_table WHERE toUserId = :userId")
    suspend fun getByUserId(userId: String): List<TopUp>

    @Query("SELECT * FROM topup_table WHERE txId = :txId")
    suspend fun getByTxId(txId: Sha256Hash): TopUp?

    @Query("SELECT * FROM topup_table WHERE txId = :txId")
    fun observe(txId: Sha256Hash): Flow<TopUp?>
    @Query("SELECT * FROM topup_table")
    suspend fun getAll(): List<TopUp>

    @Query("DELETE FROM topup_table")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM topup_table")
    suspend fun count(): Int

    @Query("SELECT * FROM topup_table WHERE creditedAt == 0")
    fun getUnused(): List<TopUp>
}
