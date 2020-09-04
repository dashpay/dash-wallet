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

    @Query("SELECT * FROM dashpay_profile where userId = :userId")
    fun load(userId: String): LiveData<DashPayProfile?>

    fun loadDistinct(userId: String):
            LiveData<DashPayProfile?> = load(userId).getDistinct()

    @Query("SELECT * FROM dashpay_profile where username = :username")
    fun loadFromUsername(username: String): LiveData<DashPayProfile?>

    fun loadFromUsernameDistinct(userId: String):
            LiveData<DashPayProfile?> = loadFromUsername(userId).getDistinct()

    @Query("DELETE FROM dashpay_profile")
    fun clear()
}