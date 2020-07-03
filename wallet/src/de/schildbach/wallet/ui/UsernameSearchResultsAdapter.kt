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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet_test.R

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context), parent)
    }

    override fun getItemCount(): Int {
        return results.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(results[position])
    }

    inner class ViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.dashpay_profile_row, parent, false)) {

        private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
        private val username by lazy { itemView.findViewById<TextView>(R.id.username) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }
        private val requestStatus by lazy { itemView.findViewById<TextView>(R.id.request_status) }
        private val buttons by lazy { itemView.findViewById<LinearLayout>(R.id.buttons) }
        private val contactAdded by lazy { itemView.findViewById<ImageView>(R.id.contact_added) }
        private val acceptRequest by lazy { itemView.findViewById<Button>(R.id.accept_contact_request) }
        private val ignoreRequest by lazy { itemView.findViewById<ImageButton>(R.id.ignore_contact_request) }

        fun bind(usernameSearchResult: UsernameSearchResult) {
            val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                    usernameSearchResult.username[0])

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                displayName.text = dashPayProfile.username
                username.text = ""
            } else {
                displayName.text = dashPayProfile.displayName
                username.text = usernameSearchResult.username
            }

            if (dashPayProfile.avatarUrl.isNotEmpty()) {
                Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                        .placeholder(defaultAvatar).into(avatar)
            } else {
                avatar.background = defaultAvatar
            }

            when (usernameSearchResult.requestSent to usernameSearchResult.requestReceived) {
                //No Relationship
                false to false -> {
                    requestStatus.visibility = View.GONE
                    buttons.visibility = View.GONE
                    contactAdded.visibility = View.GONE
                }
                //Contact Established
                true to true -> {
                    requestStatus.visibility = View.GONE
                    buttons.visibility = View.GONE
                    contactAdded.visibility = View.VISIBLE
                }
                //Request Sent / Pending
                true to false -> {
                    requestStatus.visibility = View.VISIBLE
                    buttons.visibility = View.GONE
                    contactAdded.visibility = View.GONE
                }
                //Request Received
                false to true -> {
                    requestStatus.visibility = View.GONE
                    buttons.visibility = View.VISIBLE
                    contactAdded.visibility = View.GONE
                    acceptRequest.setOnClickListener {
                        onContactRequestButtonClickListener.onAcceptRequest(usernameSearchResult, adapterPosition)
                    }
                    ignoreRequest.setOnClickListener {
                        onContactRequestButtonClickListener.onIgnoreRequest(usernameSearchResult, adapterPosition)
                    }
                }
            }

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }

        }
    }
}
