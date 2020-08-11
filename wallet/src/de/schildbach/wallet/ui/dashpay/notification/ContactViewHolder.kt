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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.notification_contact_request_received_row.view.*

open class ContactViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        RecyclerView.ViewHolder(inflater.inflate(R.layout.notification_contact_request_received_row, parent, false)) {

    private val dateFormat by lazy { itemView.context.getString(R.string.transaction_row_time_text) }

    private fun formatDate(timeStamp: Long): String {
        return String.format(dateFormat,
                DateUtils.formatDateTime(itemView.context, timeStamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
                DateUtils.formatDateTime(itemView.context, timeStamp, DateUtils.FORMAT_SHOW_TIME))
    }

    fun bind(usernameSearchResult: UsernameSearchResult, isNew: Boolean, isInvitationOfEstablished: Boolean,
             onActionClickListener: OnContactActionClickListener? = null) {

        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                usernameSearchResult.username[0])

        itemView.apply {
            setBackgroundResource(if (isNew) R.drawable.selectable_round_corners else R.drawable.selectable_background_dark)
            date.text = formatDate(usernameSearchResult.date)

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                displayName.text = dashPayProfile.username
            } else {
                displayName.text = dashPayProfile.displayName
            }

            when (usernameSearchResult.type) {
                UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                    val sentDate = usernameSearchResult.toContactRequest!!.timestamp
                    val receivedDate = usernameSearchResult.fromContactRequest!!.timestamp

                    if (sentDate > receivedDate) {
                        // we accepted last
                        displayName.text = itemView.context.getString(R.string.notifications_you_have_accepted, displayName.text)
                    } else {
                        displayName.text = itemView.context.getString(R.string.notifications_contact_has_accepted, displayName.text)
                    }
                    displayName.maxLines = 2
                    displayName.textSize = 14.0f
                    contact_added.visibility = View.VISIBLE
                    val scale: Float = itemView.resources.displayMetrics.density
                    itemView.layoutParams.height = (79 * scale + 0.5f).toInt()
                    center_guideline.setGuidelinePercent(0.473f)
                }
                UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                    displayName.text = itemView.context.getString(R.string.notifications_you_received, displayName.text)
                    contact_added.visibility = View.GONE
                }
                UsernameSearchResult.Type.REQUEST_SENT -> {
                    displayName.text = itemView.context.getString(R.string.notifications_you_sent, displayName.text)
                    contact_added.visibility = View.GONE
                }
            }

            if (dashPayProfile.avatarUrl.isNotEmpty()) {
                Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                        .placeholder(defaultAvatar).into(avatar)
            } else {
                avatar.background = defaultAvatar
            }

            if (usernameSearchResult.isPendingRequest && !isInvitationOfEstablished) {
                accept_contact_request.visibility = View.VISIBLE
                ignore_contact_request.visibility = View.VISIBLE
            } else {
                accept_contact_request.visibility = View.GONE
                ignore_contact_request.visibility = View.GONE
            }
            accept_contact_request.setOnClickListener {
                onActionClickListener?.run {
                    onAcceptRequest(usernameSearchResult, adapterPosition)
                }
            }
            ignore_contact_request.setOnClickListener {
                onActionClickListener?.run {
                    onIgnoreRequest(usernameSearchResult, adapterPosition)
                }
            }
        }
    }

    interface OnContactActionClickListener {
        fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int)
        fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int)
    }
}
