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
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.data.getDistinct
import kotlinx.coroutines.flow.Flow

@Dao
interface DashPayProfileDaoAsync {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dashPayProfile: DashPayProfile)

    @Query("SELECT * FROM dashpay_profile")
    fun loadByUserId(): LiveData<List<DashPayProfile?>>

    @Query("SELECT * FROM dashpay_profile where userId = :userId")
    fun loadByUserId(userId: String): LiveData<DashPayProfile?>

    @Query("SELECT * FROM dashpay_profile where userId = :userId")
    fun observeByUserId(userId: String): Flow<DashPayProfile?>

    fun loadByUserIdDistinct(userId: String):
            LiveData<DashPayProfile?> = loadByUserId(userId).getDistinct()

    @Query("SELECT * FROM dashpay_profile where username = :username")
    fun loadByUsername(username: String): LiveData<DashPayProfile?>

    fun loadByUsernameDistinct(username: String):
            LiveData<DashPayProfile?> = loadByUsername(username).getDistinct()

    @Query("DELETE FROM dashpay_profile")
    fun clear()
}