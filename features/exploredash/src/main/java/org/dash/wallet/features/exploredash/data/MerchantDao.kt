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

    @Query("""SELECT * FROM Merchant""")
    fun observe(): Flow<List<Merchant>>

    @Query("""
        SELECT *
        FROM merchant
        JOIN merchant_fts ON merchant.id = merchant_fts.docid
        WHERE merchant_fts MATCH :query
    """)
    fun observeSearchResults(query: String): Flow<List<Merchant>>

    @Query("SELECT * FROM Merchant WHERE id = :merchantId LIMIT 1")
    suspend fun getMerchant(merchantId: Int): Merchant?

    @Query("SELECT * FROM Merchant WHERE id = :merchantId LIMIT 1")
    fun observeMerchant(merchantId: Int): Flow<Merchant?>
}