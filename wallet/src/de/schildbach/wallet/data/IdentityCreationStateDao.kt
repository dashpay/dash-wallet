package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface IdentityCreationStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(identityCreationState: IdentityCreationState)

    @Query("SELECT * FROM identity_creation_state LIMIT 1")
    fun load(): LiveData<IdentityCreationState?>

    @Query("DELETE FROM identity_creation_state")
    fun clear()

}