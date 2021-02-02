/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import org.bitcoinj.core.Sha256Hash
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identifier.Identifier
import org.dashevo.dpp.util.HashUtils

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
                      var acceptedAt: Long = 0) : Parcelable {

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
}
