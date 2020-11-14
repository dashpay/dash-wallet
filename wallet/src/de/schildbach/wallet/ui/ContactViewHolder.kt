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

import android.content.res.Resources
import android.graphics.drawable.AnimationDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dashpay_contact_row.view.*

class ContactViewHolder(inflater: LayoutInflater, parent: ViewGroup)
    : RecyclerView.ViewHolder(inflater.inflate(R.layout.dashpay_contact_row, parent, false)) {

    interface OnItemClickListener {
        fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
    }

    interface OnContactRequestButtonClickListener {
        fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int)
        fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int)
    }

    fun bind(usernameSearchResult: UsernameSearchResult, sendContactRequestWorkState: Resource<WorkInfo>?, listener: OnItemClickListener?, contactRequestButtonClickListener: OnContactRequestButtonClickListener?) {

        val dashPayProfile = usernameSearchResult.dashPayProfile
        if (dashPayProfile.displayName.isEmpty()) {
            itemView.display_name.text = dashPayProfile.username
            itemView.username.text = ""
        } else {
            itemView.display_name.text = dashPayProfile.displayName
            itemView.username.text = usernameSearchResult.username
        }

        ProfilePictureDisplay.display(itemView.avatar, dashPayProfile)

        itemView.setOnClickListener {
            listener?.onItemClicked(itemView, usernameSearchResult)
        }

        val isPendingRequest = usernameSearchResult.isPendingRequest
        itemView.setBackgroundResource(if (isPendingRequest) R.drawable.selectable_round_corners_white else R.drawable.selectable_round_corners)

        ContactRelation.process(usernameSearchResult.type, sendContactRequestWorkState, object : ContactRelation.RelationshipCallback {

            override fun none() {
                itemView.relation_state.displayedChild = 4
            }

            override fun inviting() {
                itemView.relation_state.displayedChild = 3
                itemView.pending_work_text.setText(R.string.sending_contact_request_short)
                (itemView.pending_work_icon.drawable as AnimationDrawable).start()
            }

            override fun invited() {
                itemView.relation_state.displayedChild = 1
            }

            override fun inviteReceived() {
                itemView.relation_state.displayedChild = 2
                itemView.accept_contact_request.setOnClickListener {
                    contactRequestButtonClickListener?.onAcceptRequest(usernameSearchResult, adapterPosition)
                }
                itemView.ignore_contact_request.setOnClickListener {
                    contactRequestButtonClickListener?.onIgnoreRequest(usernameSearchResult, adapterPosition)
                }
            }

            override fun acceptingInvite() {
                itemView.relation_state.displayedChild = 3
                itemView.pending_work_text.setText(R.string.accepting_contact_request_short)
                (itemView.pending_work_icon.drawable as AnimationDrawable).start()
            }

            override fun friends() {
                itemView.relation_state.displayedChild = 0
            }
        })
    }
}