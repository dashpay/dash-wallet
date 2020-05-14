package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dashPayProfile: DashPayProfile)

    @Query("SELECT * FROM dashpay_profile LIMIT 1")
    fun load(): LiveData<DashPayProfile?>

    @Query("DELETE FROM dashpay_profile")
    fun clear()
}