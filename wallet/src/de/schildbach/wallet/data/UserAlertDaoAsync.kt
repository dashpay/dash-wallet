package de.schildbach.wallet.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.schildbach.wallet.ui.dashpay.UserAlert

@Dao
interface UserAlertDaoAsync {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(userAlert: UserAlert)

    @Query("UPDATE user_alerts SET dismissed = 1 WHERE messageResourceId = :id")
    fun dismiss(id: Int)

    @Query("SELECT * FROM user_alerts WHERE dismissed = 0 LIMIT 1")
    fun load(): LiveData<UserAlert?>

    @Query("DELETE FROM user_alerts")
    fun clear()

}