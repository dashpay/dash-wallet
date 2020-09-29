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

import android.graphics.drawable.AnimationDrawable
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.work.WorkInfo
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.NotificationItemContact
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.notification_contact_request_received_row.view.*
import java.text.SimpleDateFormat
import java.util.*

open class ContactViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        NotificationViewHolder(R.layout.notification_contact_request_received_row, inflater, parent) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy KK:mm a", Locale.getDefault())

    private fun formatDate(timeStamp: Long): String {
        return dateFormat.format(timeStamp).replace("AM", "am").replace("PM", "pm")
    }

    fun bind(notificationItem: NotificationItemContact, state: Resource<WorkInfo>?, isNew: Boolean,
             showAvatar: Boolean, onActionClickListener: OnContactActionClickListener? = null) {

        val usernameSearchResult = notificationItem.usernameSearchResult

        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                usernameSearchResult.username[0])

        itemView.apply {
            setBackgroundResource(if (isNew) R.drawable.selectable_round_corners else R.drawable.selectable_round_corners_dark)
            date.text = formatDate(usernameSearchResult.date)

            val dashPayProfile = usernameSearchResult.dashPayProfile
            val name = if (dashPayProfile.displayName.isEmpty()) {
                dashPayProfile.username
            } else {
                dashPayProfile.displayName
            }

            val displayNameResId = when (usernameSearchResult.type) {
                UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                    contact_added.setImageResource(R.drawable.ic_contact_added)
                    val sentDate = usernameSearchResult.toContactRequest!!.timestamp
                    val receivedDate = usernameSearchResult.fromContactRequest!!.timestamp
                    if (sentDate > receivedDate) {
                        R.string.notifications_you_have_accepted
                    } else {
                        R.string.notifications_contact_has_accepted
                    }
                }
                UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                    contact_added.setImageResource(R.drawable.ic_add_contact)
                    R.string.notifications_you_received
                }
                UsernameSearchResult.Type.REQUEST_SENT -> {
                    contact_added.setImageResource(R.drawable.ic_add_contact)
                    R.string.notifications_you_sent
                }
                UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                    0
                }
            }

            @Suppress("DEPRECATION")
            val displayNameText = context.getString(displayNameResId, "<b>$name</b>")
            display_name.text = Html.fromHtml(displayNameText)

            val scale: Float = itemView.resources.displayMetrics.density
            itemView.layoutParams.height = (79 * scale + 0.5f).toInt()
            center_guideline.setGuidelinePercent(0.473f)

            if (dashPayProfile.avatarUrl.isNotEmpty()) {
                Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                        .placeholder(defaultAvatar).into(avatar)
            } else {
                avatar.background = defaultAvatar
            }
            avatar.visibility = if (showAvatar) View.VISIBLE else View.GONE

            if (usernameSearchResult.isPendingRequest && !notificationItem.isInvitationOfEstablished) {
                if (state != null && state.status == Status.LOADING) {
                    accept_contact_request.visibility = View.GONE
                    ignore_contact_request.visibility = View.GONE
                    contact_added.visibility = View.GONE
                    pending_work_icon.visibility = View.VISIBLE
                    (pending_work_icon.drawable as AnimationDrawable).start()
                } else {
                    accept_contact_request.visibility = View.VISIBLE
                    ignore_contact_request.visibility = View.VISIBLE
                    contact_added.visibility = View.GONE
                    pending_work_icon.visibility = View.GONE
                }
            } else {
                accept_contact_request.visibility = View.GONE
                ignore_contact_request.visibility = View.GONE
                contact_added.visibility = View.VISIBLE
                pending_work_icon.visibility = View.GONE
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
