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
import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.dpp.identifier.Identifier

/**
 * Contains the status of an invitation
 *
 * When created, the userId, txid and created at are initialized
 *
 * sentAt is set when the transaction is broadcast
 *
 * acceptedAt at is set when the invitation is accepted and used to create an identity
 *
 * if acceptedAt > 0, then the DashPayProfileDao will have the profile and username data
 */
@Parcelize
@Entity(tableName = "invitation_table")
data class Invitation(@PrimaryKey val userId: String,
                      val txid: Sha256Hash,
                      val createdAt: Long,
                      var memo: String = "",
                      var sentAt: Long = 0,
                      var acceptedAt: Long = 0,
                      var shortDynamicLink: String? = null,
                      var dynamicLink: String? = null) : Parcelable {

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

        other as Invitation

        if (userId != other.userId) return false
        if (txid != other.txid) return false
        if (createdAt != other.createdAt) return false
        if (acceptedAt != other.acceptedAt) return false

        return true
    }

    override fun hashCode(): Int {
        return txid.hashCode()
    }

    fun canSendAgain(): Boolean {
        return shortDynamicLink != null
    }
}
