package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BlockchainIdentityDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(identity: BlockchainIdentityData)

    @Query("SELECT * FROM blockchain_identity LIMIT 1")
    fun load(): LiveData<BlockchainIdentityData?>

    @Query("DELETE FROM blockchain_identity")
    fun clear()

}