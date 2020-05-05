package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IdentityCreationStateDaoAsync {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identityCreationState: IdentityCreationState)

    @Query("SELECT * FROM identity_creation_state LIMIT 1")
    suspend fun load(): IdentityCreationState?

    @Query("DELETE FROM identity_creation_state")
    suspend fun clear()
}