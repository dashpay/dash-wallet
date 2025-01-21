package de.schildbach.wallet.database.entity

import android.os.Parcelable
import androidx.room.Entity
import kotlinx.parcelize.Parcelize
import org.bitcoinj.core.Sha256Hash

/**
 * Topup record
 */
@Parcelize
@Entity(tableName = "topup_table", primaryKeys = ["txId"])
data class TopUp(
    val txId: Sha256Hash,
    val toUserId: String,
    val workId: String = "",
    val creditedAt: Long = 0
): Parcelable {
    fun used() = creditedAt != 0L
    fun notUsed() = creditedAt == 0L
}