package de.schildbach.wallet.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.android.parcel.Parcelize
import org.dashevo.dpp.document.Document

@Parcelize
@Entity(tableName = "dashpay_profile")
data class DashPayProfile(@PrimaryKey val userId: String,
                          val username: String,
                          val displayName: String = "",
                          val publicMessage: String = "",
                          val avatarUrl: String = "",
                          val createdAt: Long = 0,
                          val updatedAt: Long = 0) : Parcelable {
    companion object {
        fun fromDocument(document: Document, username: String): DashPayProfile? {
            return try {
                DashPayProfile(document.ownerId,
                        username,
                        document.data["displayName"] as String,
                        document.data["publicMessage"] as String,
                        document.data["avatarUrl"] as String,
                        if (document.createdAt != null) document.createdAt!! else 0L,
                        if (document.updatedAt != null) document.updatedAt!! else 0L)
            } catch (e: Exception) {
                null
            }
        }
    }
}