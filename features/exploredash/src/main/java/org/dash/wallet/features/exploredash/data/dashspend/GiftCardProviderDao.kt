package org.dash.wallet.features.exploredash.data.dashspend

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GiftCardProviderDao {
    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId")
    suspend fun getProvidersByMerchantId(merchantId: String): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers WHERE provider = :provider")
    suspend fun getProvidersByProviderName(provider: String): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers")
    suspend fun getAllProviders(): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId AND provider = :provider")
    suspend fun getProviderByMerchantId(merchantId: String, provider: String): GiftCardProvider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: GiftCardProvider)

    @Query("DELETE FROM gift_card_providers WHERE merchantId IN (:merchantIds)")
    suspend fun deleteByMerchantIds(merchantIds: List<String>): Int
}
