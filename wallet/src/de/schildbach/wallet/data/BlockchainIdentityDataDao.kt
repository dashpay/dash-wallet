package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockchainIdentityDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: BlockchainIdentityData)

    @Query("UPDATE blockchain_identity SET creationState = :state, creationStateErrorMessage = :creationErrorMessage WHERE id = :id")
    suspend fun updateCreationState(id: Int, state: BlockchainIdentityData.CreationState, creationErrorMessage: String?)

    @Query("SELECT * FROM blockchain_identity LIMIT 1")
    suspend fun load(): BlockchainIdentityData?

    @Query("SELECT id, creationState, creationStateErrorMessage, username, userId, restoring, creditFundingTxId, usingInvite, invite FROM blockchain_identity LIMIT 1")
    suspend fun loadBase(): BlockchainIdentityBaseData?

    @Query("DELETE FROM blockchain_identity")
    suspend fun clear()

}