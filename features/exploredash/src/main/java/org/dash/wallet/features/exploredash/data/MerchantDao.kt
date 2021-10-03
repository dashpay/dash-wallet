package org.dash.wallet.features.exploredash.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.MerchantFTS

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(list: List<Merchant>)

    @Query("SELECT * FROM Merchant WHERE :territoryFilter = '' OR territory = :territoryFilter")
    fun observe(territoryFilter: String): Flow<List<Merchant>>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query AND (:territoryFilter = '' OR merchant_fts.territory = :territoryFilter)
    """)
    fun observeSearchResults(query: String, territoryFilter: String): Flow<List<Merchant>>

    @Query("SELECT * FROM merchant WHERE id = :merchantId LIMIT 1")
    suspend fun getMerchant(merchantId: Int): Merchant?

    @Query("SELECT * FROM merchant WHERE id = :merchantId LIMIT 1")
    fun observeMerchant(merchantId: Int): Flow<Merchant?>

    @Query("SELECT DISTINCT territory FROM merchant")
    suspend fun getTerritories(): List<String>
}