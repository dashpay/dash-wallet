/*
 * Copyright (c) 2023. Dash Core Group.
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
package de.schildbach.wallet.data

import android.os.Parcelable
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import kotlinx.android.parcel.Parcelize
import java.util.ArrayList

@Parcelize
data class UsernameSearchResult(val username: String,
                                val dashPayProfile: DashPayProfile,
                                var toContactRequest: DashPayContactRequest?,
                                var fromContactRequest: DashPayContactRequest?) : Parcelable {
    val requestSent: Boolean
        get() = toContactRequest != null
    val requestReceived: Boolean
        get() = fromContactRequest != null
    val isPendingRequest: Boolean
        get() = requestReceived && !requestSent

    val date: Long // milliseconds
        get() {
            return when (type) {
                Type.CONTACT_ESTABLISHED -> {
                    fromContactRequest!!.timestamp
                }
                Type.REQUEST_SENT -> {
                    toContactRequest!!.timestamp
                }
                Type.REQUEST_RECEIVED -> {
                    fromContactRequest!!.timestamp
                }
                Type.NO_RELATIONSHIP -> {
                    0L
                }

            }
        }

    val type: Type
        get() = when (requestSent to requestReceived) {
            false to false -> Type.NO_RELATIONSHIP
            true to true -> Type.CONTACT_ESTABLISHED
            false to true -> Type.REQUEST_RECEIVED
            true to false -> Type.REQUEST_SENT
            else -> throw IllegalStateException()
        }

    enum class Type {
        NO_RELATIONSHIP,
        REQUEST_SENT,
        REQUEST_RECEIVED,
        CONTACT_ESTABLISHED
    }

    fun getIdentity(): String {
        return toContactRequest?.toUserId
            ?: fromContactRequest?.userId
            ?: throw IllegalStateException("Cannot get identity: no contact request available")
    }
}

fun ArrayList<UsernameSearchResult>.orderBy(orderBy: UsernameSortOrderBy) {
    when (orderBy) {
        UsernameSortOrderBy.DISPLAY_NAME -> this.sortBy {
            if (it.dashPayProfile.displayName.isNotEmpty())
                it.dashPayProfile.displayName.lowercase()
            else it.dashPayProfile.username.lowercase()
        }
        UsernameSortOrderBy.USERNAME -> this.sortBy {
            it.dashPayProfile.username.lowercase()
        }
        UsernameSortOrderBy.DATE_ADDED -> this.sortByDescending {
            it.date
        }
        else -> { /* ignore */ }
        //TODO: sort by last activity or date added
    }
}