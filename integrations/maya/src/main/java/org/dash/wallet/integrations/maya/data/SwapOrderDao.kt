/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.integrations.maya.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.SwapOrder
import org.dash.wallet.common.data.entity.SwapOrderStatus

@Dao
interface SwapOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: SwapOrder)

    @Update
    suspend fun updateOrder(order: SwapOrder): Int

    @Query("SELECT * FROM swap_orders WHERE txId = :txId")
    suspend fun getOrder(txId: TxId): SwapOrder?

    @Query("SELECT * FROM swap_orders WHERE txId = :txId")
    fun observeOrder(txId: TxId): Flow<SwapOrder?>

    @Query("SELECT * FROM swap_orders WHERE status IN (:statuses)")
    suspend fun getOrdersWithStatus(statuses: List<SwapOrderStatus>): List<SwapOrder>

    @Query("SELECT * FROM swap_orders WHERE status IN (:statuses)")
    fun observeOrdersWithStatus(statuses: List<SwapOrderStatus>): Flow<List<SwapOrder>>

    @MapInfo(keyColumn = "txId")
    @Query("SELECT * FROM swap_orders")
    fun observeOrders(): Flow<Map<TxId, SwapOrder>>
}
