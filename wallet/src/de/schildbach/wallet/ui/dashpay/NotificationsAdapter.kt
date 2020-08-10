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
import de.schildbach.wallet.ui.dashpay.notification.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.wallet.Wallet
import org.dashevo.dpp.util.HashUtils
import java.math.BigInteger
import java.util.*

class NotificationsAdapter(val context: Context, val wallet: Wallet, val onContactRequestButtonClickListener: ContactRequestViewHolder.OnContactRequestButtonClickListener)
    : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val NOTIFICATION_HEADER = 1
        const val NOTIFICATION_EMPTY = 2
        const val NOTIFICATION_CONTACT_ADDED = 3
        const val NOTIFICATION_CONTACT_REQUEST_RECEIVED = 4
        const val NOTIFICATION_PAYMENT = 5
    }

    interface ViewItem
    data class HeaderViewItem(val titleResId: Int) : ViewItem
    data class ImageViewItem(val imageResId: Int, val textResId: Int) : ViewItem
    data class NotificationViewItem(val notificationItem: NotificationItem, val isNew: Boolean = false) : ViewItem

    interface OnItemClickListener {
        fun onItemClicked(view: View, notificationItem: NotificationItem)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            NOTIFICATION_HEADER -> HeaderViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_EMPTY -> ImageViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT_REQUEST_RECEIVED -> ContactRequestViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT_ADDED -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_PAYMENT -> TransactionViewHolder(LayoutInflater.from(parent.context), parent)
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
        return when (getItemViewType(position)) {
            NOTIFICATION_HEADER -> 1L
            NOTIFICATION_EMPTY -> 2L
            NOTIFICATION_CONTACT_REQUEST_RECEIVED -> getLongValue(getAsNotificationViewItem(position).notificationItem.id)
            NOTIFICATION_CONTACT_ADDED -> getLongValue(getAsNotificationViewItem(position).notificationItem.id)
            NOTIFICATION_PAYMENT -> getLongValue(getAsNotificationViewItem(position).notificationItem.id)
            else -> throw IllegalArgumentException("Invalid viewType ${getItemViewType(position)}")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            NOTIFICATION_CONTACT_REQUEST_RECEIVED -> {
                val usernameSearchResult = getAsNotificationViewItem(position).notificationItem.usernameSearchResult!!
                (holder as ContactRequestViewHolder).bind(
                        usernameSearchResult, getAsNotificationViewItem(position).isNew,
                        onContactRequestButtonClickListener)
            }
            NOTIFICATION_CONTACT_ADDED -> {
                val usernameSearchResult = getAsNotificationViewItem(position).notificationItem.usernameSearchResult!!
                (holder as ContactViewHolder).bind(
                        usernameSearchResult, getAsNotificationViewItem(position).isNew)
            }
            NOTIFICATION_HEADER -> (holder as HeaderViewHolder).bind(results[position] as HeaderViewItem)
            NOTIFICATION_EMPTY -> (holder as ImageViewHolder).bind(results[position] as ImageViewItem)
            NOTIFICATION_PAYMENT -> {
                val tx = getAsNotificationViewItem(position).notificationItem.tx!!
                val txCache = transactionCache[tx.txId]
                (holder as TransactionViewHolder).bind(tx, txCache, wallet)?.let {
                    transactionCache[tx.txId] = it
                }
            }
            else -> throw IllegalArgumentException("Invalid viewType ${getItemViewType(position)}")
        }
        if (results[position] is NotificationViewItem) {
            itemClickListener?.let { l ->
                holder.itemView.setOnClickListener {
                    l.onItemClicked(it, getAsNotificationViewItem(position).notificationItem)
                }
            }
        }
    }

    fun getAsNotificationViewItem(position: Int): NotificationViewItem {
        return (results[position] as NotificationViewItem)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any?>) {
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
            else -> {
                val notificationItem = getAsNotificationViewItem(position).notificationItem
                when (notificationItem.type) {
                    NotificationItem.Type.CONTACT_REQUEST,
                    NotificationItem.Type.CONTACT -> return when (notificationItem.usernameSearchResult!!.requestSent to notificationItem.usernameSearchResult.requestReceived) {
                        true to true -> {
                            NOTIFICATION_CONTACT_ADDED
                        }
                        false to true -> {
                            NOTIFICATION_CONTACT_REQUEST_RECEIVED
                        }
                        else -> throw IllegalArgumentException("View not supported")
                    }
                    NotificationItem.Type.PAYMENT -> NOTIFICATION_PAYMENT
                }
            }
        }
    }

    private val transactionCache: HashMap<Sha256Hash, TransactionViewHolder.TransactionCacheEntry> = HashMap()
}
