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
import android.view.View
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.NotificationItemContact
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.NotificationContactRequestReceivedRowBinding
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay

open class ContactViewHolder(val binding: NotificationContactRequestReceivedRowBinding) :
        NotificationViewHolder(binding.root) {

    fun bind(notificationItem: NotificationItemContact, state: Resource<WorkInfo>?, isNew: Boolean, isFirst: Boolean,
             recentlyModified: Boolean, showAvatar: Boolean,
             acceptRequest: ((UsernameSearchResult, Int) -> Unit)?,
             ignoreRequest: ((UsernameSearchResult, Int) -> Unit)?) {

        val usernameSearchResult = notificationItem.usernameSearchResult

        binding.apply {
            itemView.setBackgroundResource(
                when {
                    isNew && isFirst -> R.drawable.selectable_top_round_corners_light_blue
                    isNew -> R.drawable.selectable_background_light_blue
                    isFirst -> R.drawable.selectable_top_round_corners_dark
                    else -> R.drawable.selectable_background_dark
                }
            )
            val layoutParams = itemView.layoutParams as RecyclerView.LayoutParams
            if (isFirst) {
                layoutParams.bottomMargin = 0
            } else {
                layoutParams.topMargin = 0
                layoutParams.bottomMargin = 0
            }


            date.text = WalletUtils.formatDate(usernameSearchResult.date)

            val dashPayProfile = usernameSearchResult.dashPayProfile

            val displayNameResId = when (usernameSearchResult.type) {
                UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                    contactAdded.setImageResource(R.drawable.ic_contact_added)
                    val sentDate = usernameSearchResult.toContactRequest!!.timestamp
                    val receivedDate = usernameSearchResult.fromContactRequest!!.timestamp
                    if (sentDate > receivedDate) {
                        R.string.notifications_you_have_accepted
                    } else {
                        R.string.notifications_contact_has_accepted
                    }
                }
                UsernameSearchResult.Type.REQUEST_RECEIVED -> {
                    contactAdded.setImageResource(R.drawable.ic_add_contact)
                    R.string.notifications_you_received
                }
                UsernameSearchResult.Type.REQUEST_SENT -> {
                    contactAdded.setImageResource(R.drawable.ic_add_contact)
                    R.string.notifications_you_sent
                }
                UsernameSearchResult.Type.NO_RELATIONSHIP -> {
                    0
                }
            }

            if (isNew) {
                displayName.maxLines = 2
            } else {
                displayName.maxLines = 3
            }

            @Suppress("DEPRECATION")
            val displayNameText = itemView.context.getString(displayNameResId, "<b>${dashPayProfile.username}</b>")
            displayName.text = HtmlCompat.fromHtml(displayNameText, HtmlCompat.FROM_HTML_MODE_COMPACT)

            ProfilePictureDisplay.display(avatar, dashPayProfile)
            avatar.visibility = if (showAvatar) View.VISIBLE else View.GONE

            if (usernameSearchResult.isPendingRequest && !notificationItem.isInvitationOfEstablished) {
                if (state != null && state.status == Status.LOADING) {
                    //Loading
                    acceptContactRequest.visibility = View.GONE
                    ignoreContactRequest.visibility = View.GONE
                    contactAdded.visibility = View.GONE
                    pendingWorkIcon.visibility = View.VISIBLE
                    (pendingWorkIcon.drawable as AnimationDrawable).start()
                } else {
                    //Pending
                    acceptContactRequest.visibility = View.VISIBLE
                    ignoreContactRequest.visibility = View.GONE // this feature is not implemented
                    contactAdded.visibility = View.GONE
                    pendingWorkIcon.visibility = View.GONE
                }
            } else {
                //Added - Success
                buttons.visibility = if (isNew || recentlyModified) View.VISIBLE else View.GONE
                acceptContactRequest.visibility = View.GONE
                ignoreContactRequest.visibility = View.GONE
                contactAdded.visibility = View.VISIBLE
                pendingWorkIcon.visibility = View.GONE
            }
            acceptContactRequest.setOnClickListener {
                acceptRequest?.invoke(usernameSearchResult, bindingAdapterPosition)
            }
            ignoreContactRequest.setOnClickListener {
                ignoreRequest?.invoke(usernameSearchResult, bindingAdapterPosition)
            }
        }
    }
}
