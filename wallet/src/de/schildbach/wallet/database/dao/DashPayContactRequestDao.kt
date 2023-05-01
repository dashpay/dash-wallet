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
import de.schildbach.wallet.database.entity.DashPayContactRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface DashPayContactRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dashPayContactRequest: DashPayContactRequest)

    @Query("SELECT * FROM dashpay_contact_request")
    suspend fun loadAll(): List<DashPayContactRequest>

    @Query("SELECT * FROM dashpay_contact_request WHERE userId = :userId")
    suspend fun loadToOthers(userId: String): List<DashPayContactRequest>

    @Query("SELECT * FROM dashpay_contact_request WHERE userId = :userId")
    fun observeToOthers(userId: String): Flow<List<DashPayContactRequest>>

    @Query("SELECT * FROM dashpay_contact_request WHERE toUserId = :toUserId")
    suspend fun loadFromOthers(toUserId: String): List<DashPayContactRequest>

    @Query("SELECT EXISTS (SELECT * FROM dashpay_contact_request WHERE userId = :userId AND toUserId = :toUserId AND accountReference = :accountReference)")
    suspend fun exists(userId: String, toUserId: String, accountReference: Int): Boolean

    @Query("SELECT MAX(timestamp) FROM dashpay_contact_request")
    suspend fun getLastTimestamp() : Long

    @Query("SELECT COUNT(*) FROM dashpay_contact_request")
    suspend fun countAllRequests(): Int

    @Query("DELETE FROM dashpay_contact_request")
    suspend fun clear()
}