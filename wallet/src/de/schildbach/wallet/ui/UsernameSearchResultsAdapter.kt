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

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.amulyakhare.textdrawable.TextDrawable
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.dashpay.AvatarImageView
import de.schildbach.wallet_test.R

class UsernameSearchResultsAdapter() : RecyclerView.Adapter<UsernameSearchResultsAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
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

        private val avatar by lazy { itemView.findViewById<AvatarImageView>(R.id.avatar) }
        private val username by lazy { itemView.findViewById<TextView>(R.id.username) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }

        fun bind(usernameSearchResult: UsernameSearchResult) {
            username.text = usernameSearchResult.username

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                displayName.text = dashPayProfile.username
                username.text = ""
            } else {
                displayName.text = dashPayProfile.displayName
                username.text = usernameSearchResult.username
            }

            if(dashPayProfile.avatarUrl.isNotEmpty()) {
                avatar.setUrl(dashPayProfile.avatarUrl)
            } else {
                avatar.setDefaultUserAvatar(dashPayProfile.username.toUpperCase())
            }

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }
        }
    }
}
