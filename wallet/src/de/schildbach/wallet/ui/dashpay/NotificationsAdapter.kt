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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.NotificationItemContact
import de.schildbach.wallet.data.NotificationItemPayment
import de.schildbach.wallet.data.NotificationItemStub
import de.schildbach.wallet.ui.dashpay.notification.*
import de.schildbach.wallet.util.PlatformUtils
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.wallet.Wallet
import java.util.*

class NotificationsAdapter(val context: Context, val wallet: Wallet, private val showAvatars: Boolean = false,
                           private val onContactActionClickListener: ContactViewHolder.OnContactActionClickListener,
                           private val itemClickListener: OnItemClickListener,
                           private val fromProfile: Boolean = false,
                           private val fromStrangerQr: Boolean = false)

    : RecyclerView.Adapter<NotificationViewHolder>() {

    companion object {
        const val NOTIFICATION_HEADER = 1
        const val NOTIFICATION_EMPTY = 2
        const val NOTIFICATION_CONTACT = 3
        const val NOTIFICATION_PAYMENT = 4
    }

    open class NotificationViewItem(val notificationItem: NotificationItem, val isNew: Boolean = false)
    data class HeaderViewItem(private val idValue: Int, val textResId: Int) : NotificationViewItem(NotificationItemStub(idValue.toString()))
    data class ImageViewItem(private val idValue: Int, val textResId: Int, val imageResId: Int) : NotificationViewItem(NotificationItemStub(idValue.toString()))

    init {
        setHasStableIds(true)
    }

    var results: List<NotificationViewItem> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val transactionCache: HashMap<Sha256Hash, TransactionViewHolder.TransactionCacheEntry> = HashMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        return when (viewType) {
            NOTIFICATION_HEADER -> if (fromProfile) {
                ProfileActivityHeaderHolder(LayoutInflater.from(parent.context), parent, fromStrangerQr)
            } else {
                HeaderViewHolder(LayoutInflater.from(parent.context), parent)
            }
            NOTIFICATION_EMPTY -> ImageViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_PAYMENT -> TransactionViewHolder(LayoutInflater.from(parent.context), parent)
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    override fun getItemId(position: Int): Long {
        return PlatformUtils.longHashFromEncodedString(getItem(position).notificationItem.getId())
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        val notificationViewItem = getItem(position)
        val notificationItem = notificationViewItem.notificationItem
        when (getItemViewType(position)) {
            NOTIFICATION_HEADER -> {
                holder.bind(notificationViewItem.notificationItem, (notificationViewItem as HeaderViewItem).textResId)
            }
            NOTIFICATION_EMPTY -> {
                holder.bind(notificationItem, (notificationViewItem as ImageViewItem).textResId, notificationViewItem.imageResId)
            }
            NOTIFICATION_CONTACT -> {
                holder.bind(notificationItem, notificationViewItem.isNew, showAvatars, onContactActionClickListener)
            }
            NOTIFICATION_PAYMENT -> {
                holder.bind(notificationItem, transactionCache, wallet)
            }
            else -> throw IllegalArgumentException("Invalid viewType ${getItemViewType(position)}")
        }
        holder.itemView.setOnClickListener {
            itemClickListener.run {
                onItemClicked(it, notificationItem)
            }
        }
    }

    fun getItem(position: Int): NotificationViewItem {
        return results[position]
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = results[position]
        return when {
            (item is HeaderViewItem) -> NOTIFICATION_HEADER
            (item is ImageViewItem) -> NOTIFICATION_EMPTY
            else -> when (getItem(position).notificationItem) {
                is NotificationItemContact -> NOTIFICATION_CONTACT
                is NotificationItemPayment -> NOTIFICATION_PAYMENT
                else -> throw IllegalStateException("Unsupported item type $item")
            }
        }
    }

    interface OnItemClickListener {
        fun onItemClicked(view: View, notificationItem: NotificationItem)
    }
}
