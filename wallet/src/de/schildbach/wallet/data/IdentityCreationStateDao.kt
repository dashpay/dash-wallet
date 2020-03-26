package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IdentityCreationStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(identityCreationState: IdentityCreationState)

    @Query("SELECT * FROM identity_creation_state LIMIT 1")
    fun load(): LiveData<IdentityCreationState?>

}