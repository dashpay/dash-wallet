package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayContactRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dashPayProfile: DashPayContactRequest)

    @Query("SELECT * FROM dashpay_contact_request")
    fun loadAll(): LiveData<DashPayContactRequest?>

    @Query("DELETE FROM dashpay_contact_request")
    fun clear()
}