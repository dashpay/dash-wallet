package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dashPayProfile: DashPayProfile)

    @Query("SELECT * FROM dashpay_profile WHERE userId = :userId")
    suspend fun loadByUserId(userId: String): DashPayProfile?

    @Query("SELECT * FROM dashpay_profile WHERE username = :username")
    suspend fun loadByUsername(username: String): DashPayProfile?

    @Query("SELECT * FROM dashpay_profile")
    suspend fun loadAll(): List<DashPayProfile>

    @Query("DELETE FROM dashpay_profile")
    suspend fun clear()
}