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

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.data.getDistinct

@Dao
interface DashPayContactRequestDaoAsync {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dashPayProfile: DashPayContactRequest)

    @Query("SELECT * FROM dashpay_contact_request")
    fun loadAll(): LiveData<DashPayContactRequest?>

    @Query("SELECT * FROM dashpay_contact_request WHERE userId = :userId")
    fun loadToOthers(userId: String): LiveData<List<DashPayContactRequest?>>

    @Query("SELECT * FROM dashpay_contact_request WHERE toUserId = :toUserId")
    fun loadFromOthers(toUserId: String): LiveData<List<DashPayContactRequest?>>

    fun loadDistinctToOthers(id: String):
            LiveData<List<DashPayContactRequest?>> = loadToOthers(id).getDistinct()

    fun loadDistinctFromOthers(id: String):
            LiveData<List<DashPayContactRequest?>> = loadFromOthers(id).getDistinct()

    @Query("DELETE FROM dashpay_contact_request")
    fun clear()
}