package de.schildbach.wallet.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.android.parcel.Parcelize
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identifier.Identifier

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

        private fun getField(document: Document, field: String, defaultValue: String = ""): String {
            return if (document.data.containsKey(field)) {
                document.data[field] as String
            } else {
                defaultValue
            }
        }

        fun fromDocument(document: Document, username: String): DashPayProfile? {

            val displayName = getField(document, "displayName")
            val publicMessage = getField(document, "publicMessage")
            val avatarUrl = getField(document, "avatarUrl")

            return DashPayProfile(document.ownerId.toString(),
                    username,
                    displayName,
                    publicMessage,
                    avatarUrl,
                    if (document.createdAt != null) document.createdAt!! else 0L,
                    if (document.updatedAt != null) document.updatedAt!! else 0L)
        }
    }

    @delegate:Ignore
    val userIdentifier by lazy {
        Identifier.from(userId)
    }
    @delegate:Ignore
    val rawUserId by lazy {
        userIdentifier.toBuffer()
    }
}
