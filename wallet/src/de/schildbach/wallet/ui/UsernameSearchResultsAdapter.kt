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
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
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

        private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
        private val username by lazy { itemView.findViewById<TextView>(R.id.username) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }

        fun bind(usernameSearchResult: UsernameSearchResult) {
            username.text = usernameSearchResult.username

            /*val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.data.containsKey("displayName")) {
                displayName.text = dashPayProfile.data["displayName"] as String
            }
            username.text = usernameSearchResult.username
            Glide.with(avatar).load(dashPayProfile.data["avatarUrl"]).circleCrop()
                    .placeholder(R.drawable.user5).into(avatar)

             */

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                displayName.text = dashPayProfile.username
                username.text = ""
            } else {
                displayName.text = dashPayProfile.displayName
                username.text = usernameSearchResult.username
            }

            if(dashPayProfile.avatarUrl.isNotEmpty()) {
                Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                        .placeholder(R.drawable.user5).into(avatar)
            } else {
                setDefaultUserAvatar(dashPayProfile.username.toUpperCase())
            }

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }
        }

        // TODO: how do we refactor this, the code is in three places
        private fun setDefaultUserAvatar(letters: String) {
            val dashpayUserAvatar: ImageView = itemView.findViewById(R.id.avatar)
            dashpayUserAvatar.visibility = View.VISIBLE
            val hsv = FloatArray(3)
            //Ascii codes for A: 65 - Z: 90, 0: 48 - 9: 57
            val firstChar = letters[0].toFloat()
            val charIndex: Float
            charIndex = if (firstChar <= 57) { //57 == '9' in Ascii table
                (firstChar - 48f) / 36f // 48 == '0', 36 == total count of supported
            } else {
                (firstChar - 65f + 10f) / 36f // 65 == 'A', 10 == count of digits
            }
            hsv[0] = charIndex * 360f
            hsv[1] = 0.3f
            hsv[2] = 0.6f
            val bgColor = Color.HSVToColor(hsv)
            val defaultAvatar = TextDrawable.builder().beginConfig().textColor(Color.WHITE)
                    .useFont(ResourcesCompat.getFont(itemView.context, R.font.montserrat_regular))
                    .endConfig().buildRound(letters[0].toString(), bgColor)
            dashpayUserAvatar.background = defaultAvatar
        }

    }
}
