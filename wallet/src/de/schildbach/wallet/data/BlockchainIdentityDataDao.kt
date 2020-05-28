package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockchainIdentityDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(identity: BlockchainIdentityData)

    @Query("SELECT * FROM blockchain_identity LIMIT 1")
    fun load(): LiveData<BlockchainIdentityData?>

    @Query("SELECT id, creationState, creationStateError, username, creditFundingTxId FROM blockchain_identity LIMIT 1")
    fun loadBase(): LiveData<BlockchainIdentityBaseData?>

    @Query("DELETE FROM blockchain_identity")
    fun clear()

}