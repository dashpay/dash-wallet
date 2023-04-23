package de.schildbach.wallet.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.schildbach.wallet.ui.dashpay.UserAlert

@Dao
interface UserAlertDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(userAlert: UserAlert)

    @Query("UPDATE user_alerts SET dismissed = 1 WHERE stringResId = :id")
    suspend fun dismiss(id: Int)

    @Query("SELECT * FROM user_alerts WHERE dismissed = 0 AND createdAt > :date LIMIT 1")
    suspend fun load(date: Long): UserAlert?

    @Query("DELETE FROM user_alerts")
    suspend fun clear()

}