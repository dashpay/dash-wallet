/*
 * Copyright (c) 2023. Dash Core Group.
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
import de.schildbach.wallet.ui.dashpay.UserAlert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserAlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(userAlert: UserAlert)

    @Query("UPDATE user_alerts SET dismissed = 1 WHERE stringResId = :id")
    suspend fun dismiss(id: Int)

    @Query("SELECT * FROM user_alerts WHERE dismissed = 0 AND createdAt > :fromDate LIMIT 1")
    suspend fun load(fromDate: Long): UserAlert?

    @Query("SELECT * FROM user_alerts WHERE dismissed = 0 AND createdAt > :fromDate LIMIT 1")
    fun observe(fromDate: Long): Flow<UserAlert?>

    @Query("DELETE FROM user_alerts")
    suspend fun clear()
}
