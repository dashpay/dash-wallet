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
import androidx.work.WorkInfo
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.TransactionsAdapter.TransactionHistoryItem
import de.schildbach.wallet.ui.TransactionsHeaderViewHolder
import de.schildbach.wallet.ui.dashpay.notification.ContactViewHolder
import de.schildbach.wallet.ui.dashpay.notification.HeaderViewHolder
import de.schildbach.wallet.ui.dashpay.notification.ImageViewHolder
import de.schildbach.wallet.ui.dashpay.notification.NotificationViewHolder
import de.schildbach.wallet.ui.dashpay.notification.ProfileActivityHeaderHolder
import de.schildbach.wallet.ui.dashpay.notification.TransactionViewHolder
import de.schildbach.wallet.util.PlatformUtils
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import java.util.*

class NotificationsAdapter(val context: Context, val wallet: Wallet, private val showAvatars: Boolean = false,
                           private val onContactActionClickListener: ContactViewHolder.OnContactActionClickListener,
                           private val itemClickListener: OnItemClickListener,
                           private val fromProfile: Boolean = false,
                           private val fromStrangerQr: Boolean = false)

    : RecyclerView.Adapter<NotificationViewHolder>(), ProfileActivityHeaderHolder.OnFilterListener {

    companion object {
        const val NOTIFICATION_HEADER = 1
        const val NOTIFICATION_EMPTY = 2
        const val NOTIFICATION_CONTACT = 3
        const val NOTIFICATION_PAYMENT = 4
        const val NOTIFICATION_ALERT = 5
    }

    open class NotificationViewItem(val notificationItem: NotificationItem, val isNew: Boolean = false)
    data class HeaderViewItem(private val idValue: Int, val textResId: Int) : NotificationViewItem(NotificationItemStub(idValue.toString()))
    data class ImageViewItem(private val idValue: Int, val textResId: Int, val imageResId: Int) : NotificationViewItem(NotificationItemStub(idValue.toString()))

    private var filter = ProfileActivityHeaderHolder.Filter.ALL

    init {
        setHasStableIds(true)
    }

    var results: List<NotificationViewItem> = arrayListOf()
        set(value) {
            field = value
            filteredResults.clear()
            filteredResults.addAll(value)
            notifyDataSetChanged()
        }
    var filteredResults: MutableList<NotificationViewItem> = arrayListOf()

    var sendContactRequestWorkStateMap: Map<String, Resource<WorkInfo>> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val transactionCache: HashMap<Sha256Hash, TransactionViewHolder.TransactionCacheEntry> = HashMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        return when (viewType) {
            NOTIFICATION_HEADER -> if (fromProfile) {
                ProfileActivityHeaderHolder(LayoutInflater.from(parent.context), parent, this, fromStrangerQr)
            } else {
                HeaderViewHolder(LayoutInflater.from(parent.context), parent)
            }
            NOTIFICATION_EMPTY -> ImageViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_CONTACT -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_PAYMENT -> TransactionViewHolder(LayoutInflater.from(parent.context), parent)
            NOTIFICATION_ALERT -> NotificationViewHolder(R.layout.notification_alert_item, LayoutInflater.from(parent.context), parent)
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return filteredResults.size
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
                val item = notificationItem as NotificationItemContact
                val sendContactRequestWorkState = sendContactRequestWorkStateMap[item.usernameSearchResult.dashPayProfile.userId]
                (holder as ContactViewHolder).bind(item, sendContactRequestWorkState, notificationViewItem.isNew, showAvatars, onContactActionClickListener)
            }
            NOTIFICATION_PAYMENT -> {
                holder.bind(notificationItem, transactionCache, wallet)
            }
            NOTIFICATION_ALERT -> {
                //TODO
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
        return filteredResults[position]
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = filteredResults[position]
        return when {
            (item is HeaderViewItem) -> NOTIFICATION_HEADER
            (item is ImageViewItem) -> NOTIFICATION_EMPTY
            else -> when (getItem(position).notificationItem) {
                is NotificationItemContact -> NOTIFICATION_CONTACT
                is NotificationItemPayment -> NOTIFICATION_PAYMENT
                is NotificationItemAlert -> NOTIFICATION_ALERT
                else -> throw IllegalStateException("Unsupported item type $item")
            }
        }
    }

    interface OnItemClickListener {
        fun onItemClicked(view: View, notificationItem: NotificationItem)
    }

    override fun onFilter(filter: ProfileActivityHeaderHolder.Filter) {
        this.filter = filter
        filter()
    }

    private fun filter() {
        val resultTransactions: MutableList<NotificationViewItem> = ArrayList()
        for (notificationViewItem in results) {
            when (notificationViewItem) {
                is HeaderViewItem, is ImageViewItem -> {
                    resultTransactions.add(notificationViewItem)
                }
                else -> when (notificationViewItem.notificationItem) {
                    is NotificationItemPayment -> {
                        val tx = notificationViewItem.notificationItem.tx!!
                        val sent = tx.getValue(wallet).signum() < 0
                        val isInternal = tx.purpose == Transaction.Purpose.KEY_ROTATION
                        if (filter === ProfileActivityHeaderHolder.Filter.INCOMING && !sent && !isInternal
                                || filter === ProfileActivityHeaderHolder.Filter.ALL || filter === ProfileActivityHeaderHolder.Filter.OUTGOING && sent && !isInternal) {
                            resultTransactions.add(notificationViewItem)
                        }
                    }
                    is NotificationItemContact -> {
                        resultTransactions.add(notificationViewItem)
                    }
                }
            }
        }
        filteredResults.clear()
        filteredResults.addAll(resultTransactions)
        notifyDataSetChanged()
    }
}
