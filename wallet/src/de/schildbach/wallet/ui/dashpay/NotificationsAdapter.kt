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
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.Guideline
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.AddressBookProvider
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet.util.TransactionUtil
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.contact_request_row.view.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.CurrencyTextView
import org.dash.wallet.common.util.GenericUtils
import org.dashevo.dpp.util.HashUtils
import java.math.BigInteger
import java.util.*
import kotlin.math.max

class NotificationsAdapter(val context: Context, val wallet: Wallet, val onContactRequestButtonClickListener: OnContactRequestButtonClickListener) : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    companion object {
        const val NOTIFICATION_NEW_HEADER = 4
        const val NOTIFICATION_NEW_EMPTY = 5
        const val NOTIFICATION_EARLIER_HEADER = 6
        const val NOTIFICATION_CONTACT_ADDED = 7
        const val NOTIFICATION_CONTACT_REQUEST_RECEIVED = 8
        const val NOTIFICATION_PAYMENT = 9
    }

    class ViewItem(val notificationItem: NotificationItem?,
                   val viewType: Int,
                   val isNew: Boolean = false)

    interface OnItemClickListener {
        fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
    }

    // TransactionViewHolder related items
    val colorBackground: Int by lazy { context.resources.getColor(R.color.bg_bright) }
    val colorBackgroundSelected: Int by lazy { context.resources.getColor(R.color.bg_panel) }
    val colorPrimaryStatus: Int by lazy { context.resources.getColor(R.color.primary_status) }
    val colorSecondaryStatus: Int by lazy { context.resources.getColor(R.color.secondary_status) }
    val colorInsignificant: Int by lazy { context.resources.getColor(R.color.fg_insignificant) }
    val colorValuePositve: Int by lazy { context.resources.getColor(R.color.colorPrimary) }
    val colorValueNegative: Int by lazy { context.resources.getColor(android.R.color.black) }
    val colorError: Int by lazy { context.resources.getColor(R.color.fg_error) }
    private var format: MonetaryFormat? = null

    init {
        setHasStableIds(true)
        format = WalletApplication.getInstance().configuration.format.noCode()
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
        return when (results[position].viewType) {
            NOTIFICATION_NEW_HEADER -> 1L
            NOTIFICATION_NEW_EMPTY -> 2L
            NOTIFICATION_CONTACT_REQUEST_RECEIVED -> getLongValue(results[position].notificationItem!!.id)
            NOTIFICATION_EARLIER_HEADER -> 3L
            NOTIFICATION_CONTACT_ADDED -> getLongValue(results[position].notificationItem!!.id)
            NOTIFICATION_PAYMENT -> getLongValue(results[position].notificationItem!!.id)
            else -> throw IllegalArgumentException("Invalid viewType ${results[position].viewType}")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (results[position].viewType) {
            NOTIFICATION_CONTACT_REQUEST_RECEIVED,
            NOTIFICATION_CONTACT_ADDED -> holder.bind(results[position].notificationItem!!.usernameSearchResult!!, results[position].isNew)
            NOTIFICATION_NEW_HEADER -> (holder as HeaderViewHolder).bind(R.string.notifications_new)
            NOTIFICATION_EARLIER_HEADER -> (holder as HeaderViewHolder).bind(R.string.notifications_earlier)
            NOTIFICATION_NEW_EMPTY -> (holder as ImageViewHolder).bind(R.drawable.ic_notification_new_empty, R.string.notifications_none_new)
            NOTIFICATION_PAYMENT -> (holder as TransactionViewHolder).bind(results[position].notificationItem!!.tx!!)
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

    fun setFormat(format: MonetaryFormat) {
        this.format = format.noCode()
        notifyDataSetChanged()
    }

    open inner class ViewHolder(resId: Int, inflater: LayoutInflater, parent: ViewGroup) :
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
                    ignore_contact_request.visibility = View.GONE
                } else {
                    accept_contact_request.visibility = View.VISIBLE
                    ignore_contact_request.visibility = View.VISIBLE
                }
                accept_contact_request.setOnClickListener {
                    onContactRequestButtonClickListener.onAcceptRequest(usernameSearchResult, adapterPosition)
                }
                ignore_contact_request.setOnClickListener {
                    onContactRequestButtonClickListener.onIgnoreRequest(usernameSearchResult, adapterPosition)
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

    interface OnContactRequestButtonClickListener {
        fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int)
        fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int)
    }

    private val transactionCache: HashMap<Sha256Hash, TransactionCacheEntry> = HashMap()

    private class TransactionCacheEntry constructor(val value: Coin, val sent: Boolean, val self: Boolean, val showFee: Boolean, val address: Address?,
                                                    val addressLabel: String?, val type: Transaction.Type)

    inner class TransactionViewHolder(inflater: LayoutInflater, parent: ViewGroup) : ViewHolder(R.layout.notification_transaction_row, inflater, parent) {
        private val primaryStatusView: TextView = itemView.findViewById(R.id.transaction_row_primary_status) as TextView
        private val secondaryStatusView: TextView = itemView.findViewById(R.id.transaction_row_secondary_status) as TextView
        private val timeView: TextView = itemView.findViewById(R.id.transaction_row_time) as TextView
        private val dashSymbolView: ImageView = itemView.findViewById(R.id.dash_amount_symbol) as ImageView
        private val valueView: CurrencyTextView = itemView.findViewById(R.id.transaction_row_value) as CurrencyTextView
        private val signalView: TextView = itemView.findViewById(R.id.transaction_amount_signal) as TextView
        private val fiatView: CurrencyTextView = itemView.findViewById(R.id.transaction_row_fiat) as CurrencyTextView
        private val rateNotAvailableView: TextView

        fun bind(tx: Transaction) {
            if (itemView is CardView) {
                itemView.setCardBackgroundColor(if (itemView.isActivated()) colorBackgroundSelected else colorBackground)
            }
            val confidence = tx.confidence
            val fee = tx.fee
            var txCache: TransactionCacheEntry? = transactionCache[tx.txId]
            if (txCache == null) {
                val value = tx.getValue(wallet)
                val sent = value.signum() < 0
                val self = WalletUtils.isEntirelySelf(tx, wallet)
                val showFee = sent && fee != null && !fee.isZero
                val address: Address?
                address = if (sent) {
                    val addresses = WalletUtils.getToAddressOfSent(tx, wallet)
                    if (addresses.isEmpty()) null else addresses[0]
                } else {
                    WalletUtils.getWalletAddressOfReceived(tx, wallet)
                }
                val addressLabel = if (address != null) AddressBookProvider.resolveLabel(context, address.toBase58()) else null
                val txType = tx.type
                txCache = TransactionCacheEntry(value, sent, self, showFee, address, addressLabel, txType)
                transactionCache.put(tx.txId, txCache)
            }

            //
            // Assign the colors of text and values
            //
            val primaryStatusColor: Int
            val secondaryStatusColor: Int
            val valueColor: Int
            if (confidence.hasErrors()) {
                primaryStatusColor = colorError
                secondaryStatusColor = colorError
                valueColor = colorError
            } else {
                primaryStatusColor = colorPrimaryStatus
                secondaryStatusColor = colorSecondaryStatus
                valueColor = if (txCache.sent) colorValueNegative else colorValuePositve
            }

            //
            // Set the time. eg.  "On <date> at <time>"
            //
            val time = tx.updateTime
            val onTimeText: String = context.getString(R.string.transaction_row_time_text)
            timeView.text = String.format(onTimeText,
                    DateUtils.formatDateTime(context, time.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
                    DateUtils.formatDateTime(context, time.time, DateUtils.FORMAT_SHOW_TIME))

            //
            // Set primary status - Sent:  Sent, Masternode Special Tx's, Internal
            //                  Received:  Received, Mining Rewards, Masternode Rewards
            //
            val idPrimaryStatus = TransactionUtil.getTransactionTypeName(tx, wallet)
            primaryStatusView.setText(idPrimaryStatus)
            primaryStatusView.setTextColor(primaryStatusColor)

            //
            // Set the value.  [signal] D [value]
            // signal is + or -, or not visible if the value is zero (internal or other special transactions)
            // D is the Dash Symbol
            // value has no sign.  It is zero for internal or other special transactions
            //
            valueView.setFormat(format)
            val value: Coin
            value = if (txCache.showFee) txCache.value.add(fee) else txCache.value
            valueView.visibility = View.VISIBLE
            signalView.visibility = if (!value.isZero) View.VISIBLE else View.GONE
            dashSymbolView.visibility = View.VISIBLE
            valueView.setTextColor(valueColor)
            signalView.setTextColor(valueColor)
            dashSymbolView.setColorFilter(valueColor)
            when {
                value.isPositive -> {
                    signalView.text = String.format("%c", Constants.CURRENCY_PLUS_SIGN)
                    valueView.setAmount(value)
                }
                value.isNegative -> {
                    signalView.text = String.format("%c", Constants.CURRENCY_MINUS_SIGN)
                    valueView.setAmount(value.negate())
                }
                else -> {
                    valueView.setAmount(Coin.ZERO)
                }
            }

            // fiat value
            if (!value.isZero) {
                val exchangeRate = tx.exchangeRate
                if (exchangeRate != null) {
                    val exchangeCurrencyCode = GenericUtils.currencySymbol(exchangeRate.fiat.currencyCode)
                    fiatView.setFiatAmount(txCache.value, exchangeRate, de.schildbach.wallet.Constants.LOCAL_FORMAT,
                            exchangeCurrencyCode)
                    fiatView.visibility = View.VISIBLE
                    rateNotAvailableView.visibility = View.GONE
                } else {
                    fiatView.visibility = View.GONE
                    rateNotAvailableView.visibility = View.VISIBLE
                }
            } else {
                fiatView.visibility = View.GONE
                rateNotAvailableView.visibility = View.GONE
            }

            //
            // Show the secondary status:
            //
            var secondaryStatusId = -1
            if (confidence.hasErrors()) secondaryStatusId = TransactionUtil.getErrorName(tx) else if (!txCache.sent) secondaryStatusId = TransactionUtil.getReceivedStatusString(tx, wallet)
            if (secondaryStatusId != -1) secondaryStatusView.setText(secondaryStatusId) else secondaryStatusView.text = null
            secondaryStatusView.setTextColor(secondaryStatusColor)
        }

        init {
            fiatView.setApplyMarkup(false)
            rateNotAvailableView = itemView.findViewById(R.id.transaction_row_rate_not_available) as TextView
        }
    }
}
