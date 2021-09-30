package org.dash.wallet.features.exploredash.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.model.Merchant

@Dao
interface MerchantDao {
    @Query("SELECT * FROM Merchant WHERE id = :merchantId LIMIT 1")
    suspend fun getMerchant(merchantId: Int): Merchant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(list: List<Merchant>)

    @Query("SELECT * FROM Merchant")
    fun observeAll(): Flow<List<Merchant>>

    @Query("SELECT * FROM Merchant WHERE id IN (:ids)")
    fun observeMerchants(ids: List<Int>): Flow<List<Merchant>>

    @Query("SELECT * FROM Merchant WHERE id = :merchantId LIMIT 1")
    fun observeMerchant(merchantId: Int): Flow<Merchant?>
}