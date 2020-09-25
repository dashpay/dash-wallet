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
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
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

    var pending: Map<String, Resource<Nothing>> = mapOf()
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

        fun bind(usernameSearchResult: UsernameSearchResult, state: Resource<Nothing>?) {
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

            if (state != null) {
                @Suppress("NON_EXHAUSTIVE_WHEN")
                println("state:\t$state")
                when (state.status) {
                    Status.LOADING -> {
                        // NO_RELATIONSHIP
                        itemView.request_status.visibility = View.GONE
                        itemView.buttons.visibility = View.GONE
                        itemView.contact_added.visibility = View.GONE
                        itemView.pending_work_pane.visibility = View.VISIBLE
                        (itemView.pending_work_icon.drawable as AnimationDrawable).start()
                        @Suppress("NON_EXHAUSTIVE_WHEN")
                        when (usernameSearchResult.type) {
                            UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                                itemView.pending_work_text.text = "Accepting"
                            }
                            UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                                itemView.pending_work_text.text = "Sending"
                            }
                        }
                        return
                    }
                    Status.SUCCESS -> {
                        @Suppress("NON_EXHAUSTIVE_WHEN")
                        when (usernameSearchResult.type) {
                            UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                                // CONTACT_ESTABLISHED
                                itemView.request_status.visibility = View.GONE
                                itemView.buttons.visibility = View.GONE
                                itemView.contact_added.visibility = View.VISIBLE
                                return
                            }
                            UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                                //REQUEST_SENT
                                itemView.request_status.visibility = View.VISIBLE
                                itemView.buttons.visibility = View.GONE
                                itemView.contact_added.visibility = View.GONE
                                return
                            }
                        }
                    }
                }
            }

            itemView.pending_work_pane.visibility = View.GONE
            when (usernameSearchResult.type) {
                UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                    itemView.request_status.visibility = View.GONE
                    itemView.buttons.visibility = View.GONE
                    itemView.contact_added.visibility = View.GONE
                }
                UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                    itemView.request_status.visibility = View.GONE
                    itemView.buttons.visibility = View.GONE
                    itemView.contact_added.visibility = View.VISIBLE
                }
                UsernameSearchResult.Type.REQUEST_SENT -> {
                    itemView.request_status.visibility = View.VISIBLE
                    itemView.buttons.visibility = View.GONE
                    itemView.contact_added.visibility = View.GONE
                }
                UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                    itemView.request_status.visibility = View.GONE
                    itemView.buttons.visibility = View.VISIBLE
                    itemView.contact_added.visibility = View.GONE
                    itemView.accept_contact_request.setOnClickListener {
                        onContactRequestButtonClickListener.onAcceptRequest(usernameSearchResult, adapterPosition)
                    }
                    itemView.ignore_contact_request.setOnClickListener {
                        onContactRequestButtonClickListener.onIgnoreRequest(usernameSearchResult, adapterPosition)
                    }
                }
            }
        }
    }
}
