package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockchainIdentityDataDaoAsync {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: BlockchainIdentityData)

    @Query("UPDATE blockchain_identity SET creationState = :state, creationStateError = :creationError WHERE id = :id")
    suspend fun updateCreationState(id: Int, state: BlockchainIdentityData.CreationState, creationError: Boolean)

    @Query("SELECT * FROM blockchain_identity LIMIT 1")
    suspend fun load(): BlockchainIdentityData?

    @Query("DELETE FROM blockchain_identity")
    suspend fun clear()

}