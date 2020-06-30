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
package de.schildbach.wallet.ui.dashpay

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.Guideline
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.DashPayContactRequest
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.contact_request_row.view.*
import org.dashevo.dpp.util.Entropy
import org.dashevo.dpp.util.HashUtils
import java.math.BigInteger
import java.util.*
import kotlin.math.max

class NotificationsAdapter : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    companion object {
        const val NOTIFICATION_NEW_HEADER = 4
        const val NOTIFICATION_NEW_EMPTY = 5
        const val NOTIFICATION_EARLIER_HEADER = 6
        const val NOTIFICATION_CONTACT_ADDED = 7
        const val NOTIFICATION_CONTACT_REQUEST_RECEIVED = 8
    }

    class ViewItem(val usernameSearchResult: UsernameSearchResult?,
                   val viewType: Int,
                   val isNew: Boolean = false)

    interface OnItemClickListener {
        fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
    }

    init {
        setHasStableIds(true)
    }

    var itemClickListener: OnItemClickListener? = null
    var results: List<ViewItem> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return when (viewType) {
            NOTIFICATION_NEW_HEADER -> HeaderViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_NEW_EMPTY -> ImageViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT_REQUEST_RECEIVED -> ContactRequestViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_EARLIER_HEADER -> HeaderViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT_ADDED -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun getLongValue(s: String): Long {
        val byteArray = HashUtils.byteArrayFromString(s)
        val bigInteger = BigInteger(byteArray)
        return bigInteger.toLong()
    }

    override fun getItemId(position: Int): Long {
        return when (results[position].viewType) {
            NOTIFICATION_NEW_HEADER -> 1L
            NOTIFICATION_NEW_EMPTY -> 2L
            NOTIFICATION_CONTACT_REQUEST_RECEIVED -> getLongValue(results[position].usernameSearchResult!!.fromContactRequest!!.userId)
            NOTIFICATION_EARLIER_HEADER -> 3L
            NOTIFICATION_CONTACT_ADDED -> getLongValue(results[position].usernameSearchResult!!.toContactRequest!!.toUserId)
            else -> throw IllegalArgumentException("Invalid viewType ${results[position].viewType}")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (results[position].viewType) {
            NOTIFICATION_CONTACT_REQUEST_RECEIVED,
            NOTIFICATION_CONTACT_ADDED -> holder.bind(results[position].usernameSearchResult!!, results[position].isNew)
            NOTIFICATION_NEW_HEADER -> (holder as HeaderViewHolder).bind(R.string.notifications_new)
            NOTIFICATION_EARLIER_HEADER -> (holder as HeaderViewHolder).bind(R.string.notifications_earlier)
            NOTIFICATION_NEW_EMPTY -> (holder as ImageViewHolder).bind(R.drawable.ic_notification_new_empty, R.string.notifications_none_new)
            else -> throw IllegalArgumentException("Invalid viewType ${results[position].viewType}")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }

    override fun getItemViewType(position: Int): Int {
        return results[position].viewType
    }

    fun getItemPosition(usernameSearchResult: UsernameSearchResult): Int {
        val viewItem = results.find {
            val usernameSearchResult = it.usernameSearchResult ?: false
            usernameSearchResult == it.usernameSearchResult
        }
        return results.indexOf(viewItem)
    }

    open inner class ViewHolder(resId: Int, inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(resId, parent, false)) {

        private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
        private val date by lazy { itemView.findViewById<TextView>(R.id.date) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }
        private val contactAdded by lazy { itemView.findViewById<ImageView>(R.id.contact_added) }
        private val guildline by lazy {itemView.findViewById<Guideline>(R.id.center_guideline)}
        private val dateFormat by lazy { itemView.context.getString(R.string.transaction_row_time_text) }

        private fun formatDate(timeStamp: Long): String {
            return String.format(dateFormat,
                    DateUtils.formatDateTime(itemView.context, timeStamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
                    DateUtils.formatDateTime(itemView.context, timeStamp, DateUtils.FORMAT_SHOW_TIME))
        }

        open fun bind(usernameSearchResult: UsernameSearchResult, isNew: Boolean) {
            val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                    usernameSearchResult.username[0])

            if (isNew)
                itemView.setBackgroundResource(R.drawable.selectable_round_corners)
            else
                itemView.setBackgroundResource(0) // remove background

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

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }
        }
    }


    inner class ContactViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.notification_contact_added_row, inflater, parent) {

        private val added by lazy { itemView.findViewById<ImageView>(R.id.contact_added) }

        override fun bind(usernameSearchResult: UsernameSearchResult, isNew: Boolean) {
            super.bind(usernameSearchResult, isNew)

            if (usernameSearchResult.requestSent && usernameSearchResult.requestReceived) {
                added.visibility = View.VISIBLE
            } else {
                added.visibility = View.GONE
            }
        }
    }

    inner class ContactRequestViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.notification_contact_request_received_row, inflater, parent) {

        override fun bind(usernameSearchResult: UsernameSearchResult, isNew: Boolean) {
            super.bind(usernameSearchResult, isNew)
            itemView.apply {
                if (!usernameSearchResult.isPendingRequest) {
                    accept_contact_request.visibility = View.GONE
                    hide_contract_request.visibility = View.GONE
                } else {
                    accept_contact_request.visibility = View.VISIBLE
                    hide_contract_request.visibility = View.VISIBLE
                }
                accept_contact_request.setOnClickListener {
                    //TODO: this contact request should be accepted
                    //This code is temporary to test the change in the view
                    usernameSearchResult.toContactRequest = DashPayContactRequest(Entropy.generate(), usernameSearchResult.fromContactRequest!!.toUserId,
                            usernameSearchResult.fromContactRequest!!.userId, null, Entropy.generate().toByteArray(), 0, 0, (Date().time/1000).toDouble(), false, 0 )
                    notifyItemChanged(adapterPosition)
                }

                hide_contract_request.setOnClickListener {
                    //TODO: this contact request should be hidden
                }
            }
        }
    }

    inner class ImageViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.notification_image_row, inflater, parent) {
        private val image by lazy { itemView.findViewById<ImageView>(R.id.image) }
        private val description by lazy { itemView.findViewById<TextView>(R.id.description) }
        fun bind(imageId: Int, textId: Int) {
            itemView.apply {
                image.setImageResource(imageId)
                description.text = context.getString(textId)
            }
        }
    }

    inner class HeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.notification_header_row, inflater, parent) {

        private val title by lazy { itemView.findViewById<TextView>(R.id.title) }

        fun bind(titleId: Int) {
            itemView.apply {
                title.text = context.getString(titleId)
            }
        }
    }

    interface Listener {
        fun onAcceptRequest(usernameSearchResult: UsernameSearchResult)
        fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult)
    }
}
