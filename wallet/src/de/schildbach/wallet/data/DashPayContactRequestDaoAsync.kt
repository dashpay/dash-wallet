package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayContactRequestDaoAsync {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(dashPayProfile: DashPayContactRequest)

    @Query("SELECT * FROM dashpay_contact_request")
    fun loadAll(): LiveData<DashPayContactRequest?>

    @Query("SELECT * FROM dashpay_contact_request WHERE userId = :userId")
    fun loadToOthers(userId: String): LiveData<List<DashPayContactRequest?>>

    @Query("SELECT * FROM dashpay_contact_request WHERE toUserId = :toUserId")
    fun loadFromOthers(toUserId: String): LiveData<List<DashPayContactRequest?>>

    fun loadDistinctToOthers(id: String):
            LiveData<List<DashPayContactRequest?>> = loadToOthers(id).getDistinct()

    fun loadDistinctFromOthers(id: String):
            LiveData<List<DashPayContactRequest?>> = loadFromOthers(id).getDistinct()

    @Query("DELETE FROM dashpay_contact_request")
    fun clear()
}