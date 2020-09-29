/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui

import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status

class ContactRelation {

    companion object {
        fun process(type: UsernameSearchResult.Type, state: Resource<*>?, callback: RelationshipCallback) {
            when (type) {
                UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                    if (state == null) {
                        callback.none()
                    } else {
                        when (state.status) {
                            Status.LOADING -> callback.inviting()
                            Status.SUCCESS -> callback.invited()
                            else -> callback.none()
                        }
                    }
                }
                UsernameSearchResult.Type.REQUEST_SENT -> {
                    callback.invited()
                }
                UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                    if (state == null) {
                        callback.inviteReceived()
                    } else {
                        when (state.status) {
                            Status.LOADING -> callback.acceptingInvite()
                            Status.SUCCESS -> callback.friends()
                            else -> callback.inviteReceived()
                        }
                    }
                }
                UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                    callback.friends()
                }
            }
        }
    }

    fun convert(type: UsernameSearchResult.Type, state: Resource<WorkInfo>?): Relationship {
        when (type) {
            UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                return if (state == null) {
                    Relationship.NONE
                } else {
                    when (state.status) {
                        Status.LOADING -> Relationship.INVITING
                        Status.SUCCESS -> Relationship.INVITED
                        else -> Relationship.NONE
                    }
                }
            }
            UsernameSearchResult.Type.REQUEST_SENT -> {
                return Relationship.INVITED
            }
            UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                return if (state == null) {
                    Relationship.INVITE_RECEIVED
                } else {
                    when (state.status) {
                        Status.LOADING -> Relationship.ACCEPTING_INVITE
                        Status.SUCCESS -> Relationship.FRIENDS
                        else -> Relationship.INVITE_RECEIVED
                    }
                }
            }
            UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                return Relationship.FRIENDS
            }
        }
    }

    enum class Relationship {
        FRIENDS,            // contact request sent and received
        INVITING,           // sending contact request
        INVITED,            // contact request sent
        INVITE_RECEIVED,    // contact request received
        ACCEPTING_INVITE,   // accepting contact request
        NONE                // no relationship
    }

    interface RelationshipCallback {
        fun none()              // no relationship
        fun inviting()          // sending contact request
        fun invited()           // contact request sent
        fun inviteReceived()    // contact request received
        fun acceptingInvite()   // accepting contact request
        fun friends()           // contact request sent and received
    }
}
