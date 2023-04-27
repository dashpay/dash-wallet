/*
 * Copyright 2023 Dash Core Group.
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
import de.schildbach.wallet.database.entity.Invitation
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash

@Dao
interface InvitationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(invitation: Invitation)

    @Query("SELECT * FROM invitation_table WHERE userId = :userId")
    suspend fun loadByUserId(userId: String): Invitation?

    @Query("SELECT * FROM invitation_table WHERE txid = :txid")
    suspend fun loadByUsername(txid: Sha256Hash): Invitation?

    @Query("SELECT * FROM invitation_table")
    suspend fun loadAll(): List<Invitation>

    @Query("SELECT * FROM invitation_table")
    fun observe(): Flow<List<Invitation>>

    @Query("DELETE FROM invitation_table")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM invitation_table")
    suspend fun count(): Int
}
