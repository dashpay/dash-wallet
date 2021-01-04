package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashPayContactRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dashPayContactRequest: DashPayContactRequest)

    @Query("SELECT * FROM dashpay_contact_request")
    suspend fun loadAll(): List<DashPayContactRequest>?

    @Query("SELECT * FROM dashpay_contact_request WHERE userId = :userId")
    suspend fun loadToOthers(userId: String): List<DashPayContactRequest>?

    @Query("SELECT * FROM dashpay_contact_request WHERE toUserId = :toUserId")
    suspend fun loadFromOthers(toUserId: String): List<DashPayContactRequest>?

    @Query("SELECT EXISTS (SELECT * FROM dashpay_contact_request WHERE userId = :userId AND toUserId = :toUserId AND accountReference = :accountReference)")
    suspend fun exists(userId: String, toUserId: String, accountReference: Long): Boolean

    @Query("SELECT MAX(timestamp) FROM dashpay_contact_request")
    suspend fun getLastTimestamp() : Long

    @Query("SELECT COUNT(*) FROM dashpay_contact_request")
    suspend fun countAllRequests(): Int

    @Query("DELETE FROM dashpay_contact_request")
    suspend fun clear()
}