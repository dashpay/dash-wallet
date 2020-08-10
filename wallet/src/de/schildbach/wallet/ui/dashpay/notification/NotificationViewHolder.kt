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
package de.schildbach.wallet.ui.dashpay.notification

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.Guideline
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet_test.R
import kotlin.math.max

open class NotificationViewHolder(resId: Int, inflater: LayoutInflater, parent: ViewGroup) :
        RecyclerView.ViewHolder(inflater.inflate(resId, parent, false)) {

    private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
    private val date by lazy { itemView.findViewById<TextView>(R.id.date) }
    private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }
    private val contactAdded by lazy { itemView.findViewById<ImageView>(R.id.contact_added) }
    private val guildline by lazy { itemView.findViewById<Guideline>(R.id.center_guideline) }
    private val dateFormat by lazy { itemView.context.getString(R.string.transaction_row_time_text) }

    private fun formatDate(timeStamp: Long): String {
        return String.format(dateFormat,
                DateUtils.formatDateTime(itemView.context, timeStamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
                DateUtils.formatDateTime(itemView.context, timeStamp, DateUtils.FORMAT_SHOW_TIME))
    }

    open fun bind(usernameSearchResult: UsernameSearchResult, isNew: Boolean) {
        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(
                itemView.context,
                usernameSearchResult.username[0])

        if (isNew) {
            itemView.setBackgroundResource(R.drawable.selectable_round_corners)
        } else {
            itemView.setBackgroundResource(0) // remove background
        }

        val dashPayProfile = usernameSearchResult.dashPayProfile
        if (dashPayProfile.displayName.isEmpty()) {
            displayName.text = dashPayProfile.username
        } else {
            displayName.text = dashPayProfile.displayName
        }

        when (usernameSearchResult.requestSent to usernameSearchResult.requestReceived) {
            //Contact Established
            true to true -> {
                val sentDate = usernameSearchResult.toContactRequest!!.timestamp
                val receivedDate = usernameSearchResult.fromContactRequest!!.timestamp
                val dateTime = max(sentDate, receivedDate)

                if (dateTime == sentDate) {
                    // we accepted last
                    displayName.text = itemView.context.getString(R.string.notifications_you_have_accepted, displayName.text)
                } else {
                    displayName.text = itemView.context.getString(R.string.notifications_contact_has_accepted, displayName.text)
                }
                displayName.maxLines = 2
                displayName.textSize = 14.0f
                date.text = formatDate(usernameSearchResult.date)
                contactAdded.visibility = View.VISIBLE
                val scale: Float = itemView.resources.displayMetrics.density
                itemView.layoutParams.height = (79 * scale + 0.5f).toInt()
                guildline.setGuidelinePercent(0.473f)
            }
            //Request Received
            false to true -> {
                date.text = formatDate(usernameSearchResult.date)
                contactAdded.visibility = View.GONE
            }
        }

        if (dashPayProfile.avatarUrl.isNotEmpty()) {
            Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(avatar)
        } else {
            avatar.background = defaultAvatar
        }
//
//        itemClickListener?.let { l ->
//            this.itemView.setOnClickListener {
//                l.onItemClicked(it, usernameSearchResult)
//            }
//        }
    }
}