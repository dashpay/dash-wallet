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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.AddressBookProvider
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.NotificationItemPayment
import de.schildbach.wallet.ui.transactions.TxResourceMapper
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.ui.CurrencyTextView
import org.dash.wallet.common.util.GenericUtils
import java.util.*

class TransactionViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        NotificationViewHolder(R.layout.notification_transaction_row, inflater, parent) {

    data class TransactionCacheEntry(val value: Coin, val sent: Boolean, val self: Boolean,
                                     val showFee: Boolean, val address: Address?,
                                     val addressLabel: String?, val type: Transaction.Type)

    private val colorBackground: Int by lazy { ContextCompat.getColor(itemView.context, R.color.bg_bright) }
    private val colorBackgroundSelected: Int by lazy { ContextCompat.getColor(itemView.context, R.color.bg_panel) }
    private val colorPrimaryStatus: Int by lazy { ContextCompat.getColor(itemView.context, R.color.content_primary) }
    private val colorSecondaryStatus: Int by lazy { ContextCompat.getColor(itemView.context, R.color.orange) }
    private val colorInsignificant: Int by lazy { ContextCompat.getColor(itemView.context, R.color.fg_insignificant) }
    private val colorValuePositve: Int by lazy { ContextCompat.getColor(itemView.context, R.color.colorPrimary) }
    private val colorValueNegative: Int by lazy { ContextCompat.getColor(itemView.context, android.R.color.black) }
    private val colorError: Int by lazy { ContextCompat.getColor(itemView.context, R.color.fg_error) }
    private var format = WalletApplication.getInstance().configuration.format.noCode()

    private val primaryStatusView: TextView = itemView.findViewById(R.id.transaction_row_primary_status) as TextView
    private val secondaryStatusView: TextView = itemView.findViewById(R.id.transaction_row_secondary_status) as TextView
    private val timeView: TextView = itemView.findViewById(R.id.transaction_row_time) as TextView
    private val dashSymbolView: ImageView = itemView.findViewById(R.id.dash_amount_symbol) as ImageView
    private val valueView: CurrencyTextView = itemView.findViewById(R.id.transaction_row_value) as CurrencyTextView
    private val signalView: TextView = itemView.findViewById(R.id.transaction_amount_signal) as TextView
    private val fiatView: CurrencyTextView = itemView.findViewById(R.id.transaction_row_fiat) as CurrencyTextView
    private val rateNotAvailableView: TextView
    private val txResourceMapper = TxResourceMapper()

    override fun bind(notificationItem: NotificationItem, vararg args: Any) {
        val notificationItemPayment = notificationItem as NotificationItemPayment
        val tx = notificationItemPayment.tx!!

        @Suppress("UNCHECKED_CAST")
        val transactionCache = (args[0] as HashMap<Sha256Hash, TransactionCacheEntry>)
        val wallet = (args[1] as Wallet)
        bind(tx, transactionCache, wallet)
    }

    private fun bind(tx: Transaction, transactionCache: HashMap<Sha256Hash, TransactionCacheEntry>, wallet: Wallet) {
        if (itemView is CardView) {
            (itemView as CardView).setCardBackgroundColor(if (itemView.isActivated()) colorBackgroundSelected else colorBackground)
        }

        val confidence = tx.confidence
        val fee = tx.fee

        if (!transactionCache.containsKey(tx.txId)) {
            val value = tx.getValue(wallet)
            val sent = value.signum() < 0
            val self = tx.isEntirelySelf(wallet)
            val showFee = sent && fee != null && !fee.isZero
            val address: Address?
            address = if (sent) {
                val addresses = TransactionUtils.getToAddressOfSent(tx, wallet)
                if (addresses.isEmpty()) null else addresses[0]
            } else {
                TransactionUtils.getWalletAddressOfReceived(tx, wallet)
            }
            val addressLabel = if (address != null) AddressBookProvider.resolveLabel(itemView.context, address.toBase58()) else null
            val txType = tx.type

            transactionCache[tx.txId] = TransactionCacheEntry(value, sent, self, showFee, address, addressLabel, txType)
        }
        val txCache = transactionCache[tx.txId]!!

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
        timeView.setText(WalletUtils.formatDate(time.time))

        //
        // Set primary status - Sent:  Sent, Masternode Special Tx's, Internal
        //                  Received:  Received, Mining Rewards, Masternode Rewards
        //
        val idPrimaryStatus = txResourceMapper.getTransactionTypeName(tx, wallet)
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
        if (confidence.hasErrors()) secondaryStatusId = txResourceMapper.getErrorName(tx) else if (!txCache.sent) secondaryStatusId = txResourceMapper.getReceivedStatusString(tx, wallet.context)
        if (secondaryStatusId != -1) secondaryStatusView.setText(secondaryStatusId) else secondaryStatusView.text = null
        secondaryStatusView.setTextColor(secondaryStatusColor)
    }

    init {
        fiatView.setApplyMarkup(false)
        valueView.setApplyMarkup(false)
        rateNotAvailableView = itemView.findViewById(R.id.transaction_row_rate_not_available) as TextView
    }
}