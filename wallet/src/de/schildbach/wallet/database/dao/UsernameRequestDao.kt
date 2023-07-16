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

    @Query("SELECT * FROM username_requests")
    fun observe(): Flow<List<UsernameRequest>>

    @Query(
        """
        SELECT * FROM username_requests WHERE username IN 
            (SELECT username FROM username_requests GROUP BY username HAVING COUNT(username) > 1)
        """
    )
    fun observeDuplicates(): Flow<List<UsernameRequest>>

    @Query("DELETE FROM username_requests")
    suspend fun clear()
}
