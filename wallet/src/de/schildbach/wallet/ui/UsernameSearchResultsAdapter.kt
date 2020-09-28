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
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dashpay_profile_row.view.*

class UsernameSearchResultsAdapter(private val onContactRequestButtonClickListener: OnContactRequestButtonClickListener) : RecyclerView.Adapter<UsernameSearchResultsAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
    }

    interface OnContactRequestButtonClickListener {
        fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int)
        fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int)
    }

    var itemClickListener: OnItemClickListener? = null
    var results: List<UsernameSearchResult> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var pending: Map<String, Resource<WorkInfo>> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context), parent)
    }

    override fun getItemCount(): Int {
        return results.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]
        val state = pending[item.dashPayProfile.userId]
        holder.bind(results[position], state)
    }

    inner class ViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.dashpay_profile_row, parent, false)) {

        fun bind(usernameSearchResult: UsernameSearchResult, state: Resource<WorkInfo>?) {
            val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                    usernameSearchResult.username[0])

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                itemView.displayName.text = dashPayProfile.username
                itemView.username.text = ""
            } else {
                itemView.displayName.text = dashPayProfile.displayName
                itemView.username.text = usernameSearchResult.username
            }

            if (dashPayProfile.avatarUrl.isNotEmpty()) {
                Glide.with(itemView.avatar).load(dashPayProfile.avatarUrl).circleCrop()
                        .placeholder(defaultAvatar).into(itemView.avatar)
            } else {
                itemView.avatar.background = defaultAvatar
            }

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }

            ContactRelation.process(usernameSearchResult.type, state, object : ContactRelation.RelationshipCallback {

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
                        onContactRequestButtonClickListener.onAcceptRequest(usernameSearchResult, adapterPosition)
                    }
                    itemView.ignore_contact_request.setOnClickListener {
                        onContactRequestButtonClickListener.onIgnoreRequest(usernameSearchResult, adapterPosition)
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
}
