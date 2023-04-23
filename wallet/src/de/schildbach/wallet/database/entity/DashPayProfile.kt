/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.database.entity

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import org.dashj.platform.dashpay.Profile
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.util.HashUtils

@Parcelize
@Entity(tableName = "dashpay_profile")
data class DashPayProfile(@PrimaryKey val userId: String,
                          val username: String,
                          var displayName: String = "",
                          var publicMessage: String = "",
                          var avatarUrl: String = "",
                          val avatarHash: ByteArray? = null,
                          val avatarFingerprint: ByteArray? = null,
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

        private fun getByteArrayField(document: Document, field: String, defaultValue: ByteArray? = null): ByteArray? {
            return if (document.data.containsKey(field)) {
                HashUtils.byteArrayfromBase64orByteArray(document.data[field] ?: error("This shouldn't happen."))
            } else {
                defaultValue
            }
        }

        private fun getString(value: String?) : String {
            return value ?: ""
        }

        fun fromDocument(profile: Profile, username: String): DashPayProfile? {
            return DashPayProfile(profile.ownerId.toString(),
                    username,
                    getString(profile.displayName),
                    getString(profile.publicMessage),
                    getString(profile.avatarUrl),
                    profile.avatarHash,
                    profile.avatarFingerprint)
        }

        fun fromDocument(document: Document, username: String): DashPayProfile? {

            val displayName = getField(document, "displayName")
            val publicMessage = getField(document, "publicMessage")
            val avatarUrl = getField(document, "avatarUrl")
            val avatarHash = getByteArrayField(document, "avatarHash")
            val avatarFingerprint = getByteArrayField(document, "avatarFingerprint")

            return DashPayProfile(document.ownerId.toString(),
                    username,
                    displayName,
                    publicMessage,
                    avatarUrl,
                    avatarHash,
                    avatarFingerprint,
                    if (document.createdAt != null) document.createdAt!! else 0L,
                    if (document.updatedAt != null) document.updatedAt!! else 0L)
        }
    }

    @IgnoredOnParcel
    @delegate:Ignore
    val userIdentifier by lazy {
        Identifier.from(userId)
    }
    @IgnoredOnParcel
    @delegate:Ignore
    val rawUserId by lazy {
        userIdentifier.toBuffer()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DashPayProfile

        if (userId != other.userId) return false
        if (displayName != other.displayName) return false
        if (publicMessage != other.publicMessage) return false
        if (avatarUrl != other.avatarUrl) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    val nameLabel: String
        get() = if (displayName.isEmpty()) {
            username
        } else {
            displayName
        }
}
