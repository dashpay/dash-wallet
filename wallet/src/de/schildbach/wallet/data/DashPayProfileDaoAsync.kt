package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayProfileDaoAsync {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dashPayProfile: DashPayProfile)

    @Query("SELECT * FROM dashpay_profile WHERE userId = :userId")
    suspend fun load(userId: String): DashPayProfile?

    @Query("DELETE FROM dashpay_profile")
    suspend fun clear()
}