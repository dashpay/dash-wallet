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

import androidx.room.*
import de.schildbach.wallet.database.entity.UsernameVote
import kotlinx.coroutines.flow.Flow

/**
 * For this table, username is normalized
 */
@Dao
interface UsernameVoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usernameVote: UsernameVote)

    @Query("SELECT COUNT(*) FROM username_votes WHERE username = :username")
    suspend fun countVotes(username: String): Int

    @Query("SELECT * FROM username_votes WHERE username = :username ORDER BY timestamp")
    suspend fun getVotes(username: String): List<UsernameVote>

    @Query("SELECT * FROM username_votes WHERE username = :username ORDER BY timestamp")
    fun observeVotes(username: String): Flow<List<UsernameVote>>

    @Query("DELETE FROM username_votes")
    suspend fun clear()

    @Query("DELETE FROM username_votes WHERE username == :username")
    suspend fun remove(username: String)

    @Query("SELECT * FROM username_votes")
    suspend fun getAllVotes(): List<UsernameVote>
}
