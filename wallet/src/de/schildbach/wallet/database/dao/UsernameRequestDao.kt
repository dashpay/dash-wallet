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
import de.schildbach.wallet.database.entity.UsernameRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface UsernameRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usernameRequest: UsernameRequest)

    @Update
    suspend fun update(usernameRequest: UsernameRequest)

    @Query("UPDATE username_requests SET votes = votes + :votesAmount, isApproved = 1 WHERE requestId IN (:requestIds)")
    suspend fun voteForRequests(requestIds: List<String>, votesAmount: Int)

    @Query("SELECT * FROM username_requests WHERE requestId = :requestId")
    suspend fun getRequest(requestId: String): UsernameRequest?

    @Query("SELECT * FROM username_requests WHERE requestId = :requestId")
    fun observeRequest(requestId: String): Flow<UsernameRequest?>

    @Query(
        """
        SELECT * FROM username_requests 
            WHERE (:onlyWithLinks = 0) OR (:onlyWithLinks = 1 AND link IS NOT NULL)
        ORDER BY normalizedLabel COLLATE NOCASE ASC
         """
    )
    fun observeAll(onlyWithLinks: Boolean): Flow<List<UsernameRequest>>

    @Query(
        """
        SELECT * FROM username_requests 
            WHERE normalizedLabel IN (SELECT normalizedLabel FROM username_requests GROUP BY normalizedLabel HAVING COUNT(normalizedLabel) > 1)
            AND (:onlyWithLinks = 0) OR (:onlyWithLinks = 1 AND link IS NOT NULL) 
        ORDER BY normalizedLabel COLLATE NOCASE ASC
        """
    )
    fun observeDuplicates(onlyWithLinks: Boolean): Flow<List<UsernameRequest>>

    @Query("DELETE FROM username_requests")
    suspend fun clear()

    @Query(
        """
        DELETE FROM username_requests
            WHERE requestId = :requestId;
        """
    )
    suspend fun remove(requestId: String)

    @Query(
        """
        UPDATE username_requests
            SET isApproved = false
            WHERE normalizedLabel = :normalizedLabel;
        """
    )
    suspend fun removeApproval(normalizedLabel: String)
}
