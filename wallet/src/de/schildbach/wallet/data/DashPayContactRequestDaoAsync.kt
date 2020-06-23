package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayContactRequestDaoAsync {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dashPayContactRequest: DashPayContactRequest)

    @Query("SELECT * FROM dashpay_contact_request")
    suspend fun loadAll(): List<DashPayContactRequest>?

    @Query("SELECT * FROM dashpay_contact_request WHERE userId = :userId")
    suspend fun loadToOthers(userId: String): List<DashPayContactRequest>?

    @Query("SELECT * FROM dashpay_contact_request WHERE toUserId = :toUserId")
    suspend fun loadFromOthers(toUserId: String): List<DashPayContactRequest>?

    @Query("DELETE FROM dashpay_contact_request")
    suspend fun clear()
}