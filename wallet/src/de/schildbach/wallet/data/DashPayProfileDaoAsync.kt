package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DashPayProfileDaoAsync {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dashPayProfile: DashPayProfile)

    @Query("SELECT * FROM dashpay_profile")
    fun loadByUserId(): LiveData<List<DashPayProfile?>>

    @Query("SELECT * FROM dashpay_profile where userId = :userId")
    fun loadByUserId(userId: String): LiveData<DashPayProfile?>

    @Query("SELECT * FROM dashpay_profile where userId = :userId")
    fun observeByUserId(userId: String): Flow<DashPayProfile?>

    fun loadByUserIdDistinct(userId: String):
            LiveData<DashPayProfile?> = loadByUserId(userId).getDistinct()

    @Query("SELECT * FROM dashpay_profile where username = :username")
    fun loadByUsername(username: String): LiveData<DashPayProfile?>

    fun loadByUsernameDistinct(username: String):
            LiveData<DashPayProfile?> = loadByUsername(username).getDistinct()

    @Query("DELETE FROM dashpay_profile")
    fun clear()
}