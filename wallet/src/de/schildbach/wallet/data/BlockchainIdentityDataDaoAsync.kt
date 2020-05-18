package de.schildbach.wallet.data

import androidx.room.*

@Dao
interface BlockchainIdentityDataDaoAsync {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: BlockchainIdentityData)

    @Query("SELECT * FROM blockchain_identity LIMIT 1")
    suspend fun load(): BlockchainIdentityData?

    @Query("DELETE FROM blockchain_identity")
    suspend fun clear()

}