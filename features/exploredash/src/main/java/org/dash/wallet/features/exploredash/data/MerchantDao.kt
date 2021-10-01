package org.dash.wallet.features.exploredash.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Merchant

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(list: List<Merchant>)

    @Query("""
        SELECT * FROM Merchant 
        WHERE name LIKE '%' || :query || '%' 
            OR address1 LIKE '%' || :query || '%' 
            OR address2 LIKE '%' || :query || '%' 
            OR address3 LIKE '%' || :query || '%'
            OR address4 LIKE '%' || :query || '%' 
            OR territory LIKE '%' || :query || '%'""")
    fun observe(query: String = ""): Flow<List<Merchant>>

    @Query("SELECT * FROM Merchant WHERE id = :merchantId LIMIT 1")
    suspend fun getMerchant(merchantId: Int): Merchant?

    @Query("SELECT * FROM Merchant WHERE id = :merchantId LIMIT 1")
    fun observeMerchant(merchantId: Int): Flow<Merchant?>
}