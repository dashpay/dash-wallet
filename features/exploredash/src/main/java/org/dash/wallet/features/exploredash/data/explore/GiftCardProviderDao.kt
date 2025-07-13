/*
 * Copyright 2025 Dash Core Group.
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

package org.dash.wallet.features.exploredash.data.explore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.explore.model.GiftCardProvider

@Dao
interface GiftCardProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: GiftCardProvider)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(providers: List<GiftCardProvider>)

    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId")
    suspend fun getProvidersByMerchantId(merchantId: Long): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId")
    fun getProvidersByMerchantIdFlow(merchantId: Long): Flow<List<GiftCardProvider>>

    @Query("SELECT * FROM gift_card_providers WHERE provider = :provider")
    suspend fun getProvidersByProviderName(provider: String): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers WHERE active = :active")
    suspend fun getActiveProviders(active: Boolean = true): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers")
    suspend fun getAllProviders(): List<GiftCardProvider>

    @Query("DELETE FROM gift_card_providers WHERE merchantId = :merchantId")
    suspend fun deleteByMerchantId(merchantId: Long)

    @Query("DELETE FROM gift_card_providers")
    suspend fun deleteAll()
}