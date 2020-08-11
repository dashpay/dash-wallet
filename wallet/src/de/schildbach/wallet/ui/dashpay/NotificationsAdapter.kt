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
import de.schildbach.wallet.ui.dashpay.notification.ContactViewHolder
import de.schildbach.wallet.ui.dashpay.notification.HeaderViewHolder
import de.schildbach.wallet.ui.dashpay.notification.ImageViewHolder
import de.schildbach.wallet.ui.dashpay.notification.TransactionViewHolder
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.wallet.Wallet
import org.dashevo.dpp.util.HashUtils
import java.math.BigInteger
import java.util.*

class NotificationsAdapter(val context: Context, val wallet: Wallet, private val onContactActionClickListener: ContactViewHolder.OnContactActionClickListener, private val itemClickListener: OnItemClickListener)
    : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val NOTIFICATION_HEADER = 1
        const val NOTIFICATION_EMPTY = 2
        const val NOTIFICATION_CONTACT = 3
        const val NOTIFICATION_PAYMENT = 4
    }

    interface ViewItem
    data class HeaderViewItem(val titleResId: Int) : ViewItem
    data class EmptyViewItem(val imageResId: Int, val textResId: Int) : ViewItem
    data class NotificationViewItem(val notificationItem: NotificationItem, val isNew: Boolean = false) : ViewItem

    init {
        setHasStableIds(true)
    }

    var results: List<ViewItem> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val transactionCache: HashMap<Sha256Hash, TransactionViewHolder.TransactionCacheEntry> = HashMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            NOTIFICATION_HEADER -> HeaderViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_EMPTY -> ImageViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
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
            else -> getLongValue(getNotificationItem(position).getId())
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            NOTIFICATION_HEADER -> (holder as HeaderViewHolder).bind(results[position] as HeaderViewItem)
            NOTIFICATION_EMPTY -> (holder as ImageViewHolder).bind(results[position] as EmptyViewItem)
            NOTIFICATION_CONTACT -> {
                val notificationItem = getNotificationItem(position) as NotificationItemContact
                val usernameSearchResult = notificationItem.usernameSearchResult
                (holder as ContactViewHolder).bind(
                        usernameSearchResult,
                        notificationItem.isNew,
                        notificationItem.isInvitationOfEstablished,
                        onContactActionClickListener)
            }
            NOTIFICATION_PAYMENT -> {
                val notificationItem = getNotificationItem(position) as NotificationItemPayment
                val tx = notificationItem.tx!!
                val txCache = transactionCache[tx.txId]
                (holder as TransactionViewHolder).bind(tx, txCache, wallet)?.let {
                    transactionCache[tx.txId] = it
                }
            }
            else -> throw IllegalArgumentException("Invalid viewType ${getItemViewType(position)}")
        }
        if (results[position] is NotificationViewItem) {
            holder.itemView.setOnClickListener {
                itemClickListener.run {
                    onItemClicked(it, getNotificationItem(position))
                }
            }
        }
    }

    fun getNotificationItem(position: Int): NotificationItem {
        return (results[position] as NotificationViewItem).notificationItem
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
            (item is EmptyViewItem) -> NOTIFICATION_EMPTY
            else -> when (getNotificationItem(position)) {
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
