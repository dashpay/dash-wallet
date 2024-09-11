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

import androidx.room.*
import de.schildbach.wallet.database.entity.UsernameVote
import kotlinx.coroutines.flow.Flow

@Dao
interface UsernameVoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usernameVote: UsernameVote)

    @Update
    suspend fun update(usernameVote: UsernameVote)

//    @Query("UPDATE username_votes SET votes = votes + :votesAmount, isApproved = 1 WHERE requestId IN (:requestIds)")
//    suspend fun voteForRequest(requestIds: List<String>, votesAmount: Int)

    @Query("SELECT * FROM username_votes WHERE username = :username")
    suspend fun getVotes(username: String): List<UsernameVote>

    @Query("SELECT * FROM username_votes WHERE username = :username")
    fun observeVotes(username: String): Flow<List<UsernameVote>>

    @Query("DELETE FROM username_votes")
    suspend fun clear()
}
