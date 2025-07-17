package org.dash.wallet.features.exploredash.data.dashspend

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GiftCardProviderDao {
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insert(provider: GiftCardProvider)

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertAll(providers: List<GiftCardProvider>)

    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId")
    suspend fun getProvidersByMerchantId(merchantId: String): List<GiftCardProvider>

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