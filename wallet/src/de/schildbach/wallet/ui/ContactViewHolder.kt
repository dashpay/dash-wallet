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

import android.graphics.drawable.AnimationDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DashpayContactRowBinding
import de.schildbach.wallet_test.databinding.DashpayContactRowContentBinding
import de.schildbach.wallet_test.databinding.DashpayContactSuggestionRowBinding
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay

interface OnItemClickListener {
    fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
}

interface OnContactRequestButtonClickListener {
    fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int)
    fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int)
}

class ContactViewHolder(val binding: DashpayContactRowBinding, val isSuggestion: Boolean = false, val useFriendsIcon: Boolean = true)
    : RecyclerView.ViewHolder(binding.root) {

    val contactRowBinding = DashpayContactRowContentBinding.bind(binding.contactRow)

    fun bind(usernameSearchResult: UsernameSearchResult,
             sendContactRequestWorkState: Resource<WorkInfo>?, listener: OnItemClickListener?,
             contactRequestButtonClickListener: OnContactRequestButtonClickListener?,
             networkAvailable: Boolean = true) {

        val dashPayProfile = usernameSearchResult.dashPayProfile
        if (dashPayProfile.displayName.isEmpty()) {
            contactRowBinding.displayName.text = dashPayProfile.username
            contactRowBinding.username.text = ""
        } else {
            contactRowBinding.displayName.text = dashPayProfile.displayName
            contactRowBinding.username.text = usernameSearchResult.username
        }

        ProfilePictureDisplay.display(contactRowBinding.avatar, dashPayProfile)

        itemView.setOnClickListener {
            listener?.onItemClicked(itemView, usernameSearchResult)
        }

        if (!isSuggestion) {
            val isPendingRequest = usernameSearchResult.isPendingRequest
            itemView.setBackgroundResource(if (isPendingRequest) R.drawable.selectable_round_corners_white else R.drawable.selectable_round_corners)
        }

        ContactRelation.process(usernameSearchResult.type, sendContactRequestWorkState, object : ContactRelation.RelationshipCallback {

            override fun none() {
                contactRowBinding.relationState.visibility = View.GONE
            }

            override fun inviting() {
                contactRowBinding.relationState.displayedChild = 3
                contactRowBinding.relationState.visibility = View.VISIBLE
                contactRowBinding.pendingWorkText.setText(R.string.sending_contact_request_short)
                (contactRowBinding.pendingWorkIcon.drawable as AnimationDrawable).start()
            }

            override fun invited() {
                contactRowBinding.relationState.displayedChild = 1
            }

            override fun inviteReceived() {
                contactRowBinding.relationState.displayedChild = 2
                contactRowBinding.relationState.visibility = View.VISIBLE
                contactRowBinding.acceptContactRequest.isEnabled = networkAvailable
                contactRowBinding.acceptContactRequest.setOnClickListener {
                    contactRequestButtonClickListener?.onAcceptRequest(usernameSearchResult, adapterPosition)
                }
                contactRowBinding.ignoreContactRequest.setOnClickListener {
                    contactRequestButtonClickListener?.onIgnoreRequest(usernameSearchResult, adapterPosition)
                }
            }

            override fun acceptingInvite() {
                contactRowBinding.relationState.displayedChild = 3
                contactRowBinding.relationState.visibility = View.VISIBLE
                contactRowBinding.pendingWorkText.setText(R.string.accepting_contact_request_short)
                (contactRowBinding.pendingWorkIcon.drawable as AnimationDrawable).start()
            }

            override fun friends() {
                if (useFriendsIcon) {
                    contactRowBinding.relationState.visibility = View.VISIBLE
                    contactRowBinding.relationState.displayedChild = 0
                } else {
                    none()
                }
            }
        })
    }
}

//class ContactSuggestionViewHolder(val binding: DashpayContactSuggestionRowBinding, val isSuggestion: Boolean = false, val useFriendsIcon: Boolean = true)
//    : RecyclerView.ViewHolder(binding.root) {
//
//    val contactRowBinding = ContactnRowBinding.bind(binding.contactRow)
//
//    fun bind(
//        usernameSearchResult: UsernameSearchResult,
//        sendContactRequestWorkState: Resource<WorkInfo>?, listener: OnItemClickListener?,
//        contactRequestButtonClickListener: OnContactRequestButtonClickListener?,
//        networkAvailable: Boolean = true
//    ) {
//
//        val dashPayProfile = usernameSearchResult.dashPayProfile
//        if (dashPayProfile.displayName.isEmpty()) {
//            contactRowBinding.displayName.text = dashPayProfile.username
//            contactRowBinding.username.text = ""
//        } else {
//            contactRowBinding.displayName.text = dashPayProfile.displayName
//            contactRowBinding.username.text = usernameSearchResult.username
//        }
//
//        ProfilePictureDisplay.display(contactRowBinding.avatar, dashPayProfile)
//
//        itemView.setOnClickListener {
//            listener?.onItemClicked(itemView, usernameSearchResult)
//        }
//
//        if (!isSuggestion) {
//            val isPendingRequest = usernameSearchResult.isPendingRequest
//            itemView.setBackgroundResource(if (isPendingRequest) R.drawable.selectable_round_corners_white else R.drawable.selectable_round_corners)
//        }
//
//        ContactRelation.process(
//            usernameSearchResult.type,
//            sendContactRequestWorkState,
//            object : ContactRelation.RelationshipCallback {
//
//                override fun none() {
//                    contactRowBinding.relationState.visibility = View.GONE
//                }
//
//                override fun inviting() {
//                    contactRowBinding.relationState.displayedChild = 3
//                    contactRowBinding.relationState.visibility = View.VISIBLE
//                    contactRowBinding.pendingWorkText.setText(R.string.sending_contact_request_short)
//                    (contactRowBinding.ignoreContactRequest.drawable as AnimationDrawable).start()
//                }
//
//                override fun invited() {
//                    contactRowBinding.relationState.displayedChild = 1
//                }
//
//                override fun inviteReceived() {
//                    contactRowBinding.relationState.displayedChild = 2
//                    contactRowBinding.relationState.visibility = View.VISIBLE
//                    contactRowBinding.acceptContactRequest.isEnabled = networkAvailable
//                    contactRowBinding.acceptContactRequest.setOnClickListener {
//                        contactRequestButtonClickListener?.onAcceptRequest(usernameSearchResult, adapterPosition)
//                    }
//                    contactRowBinding.ignoreContactRequest.setOnClickListener {
//                        contactRequestButtonClickListener?.onIgnoreRequest(usernameSearchResult, adapterPosition)
//                    }
//                }
//
//                override fun acceptingInvite() {
//                    contactRowBinding.relationState.displayedChild = 3
//                    contactRowBinding.relationState.visibility = View.VISIBLE
//                    contactRowBinding.pendingWorkText.setText(R.string.accepting_contact_request_short)
//                    (contactRowBinding.pendingWorkIcon.drawable as AnimationDrawable).start()
//                }
//
//                override fun friends() {
//                    if (useFriendsIcon) {
//                        contactRowBinding.relationState.visibility = View.VISIBLE
//                        contactRowBinding.relationState.displayedChild = 0
//                    } else {
//                        none()
//                    }
//                }
//            })
//    }
//}