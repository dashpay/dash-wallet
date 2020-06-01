/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.*
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.ui.CurrencyTextView

/**
 * @author Samuel Barbosa
 */
class TransactionResultViewBinder(private val containerView: View) {

    private val ctx by lazy { containerView.context }
    private val checkIcon by lazy { containerView.findViewById<ImageView>(R.id.check_icon) }
    private val transactionAmountSignal by lazy { containerView.findViewById<TextView>(R.id.transaction_amount_signal) }
    private val dashAmountSymbol by lazy { containerView.findViewById<ImageView>(R.id.dash_amount_symbol) }
    private val transactionTitle by lazy { containerView.findViewById<TextView>(R.id.transaction_title) }
    private val dashAmount by lazy { containerView.findViewById<CurrencyTextView>(R.id.dash_amount) }
    private val transactionFee by lazy { containerView.findViewById<CurrencyTextView>(R.id.transaction_fee) }
    private val fiatValue by lazy { containerView.findViewById<CurrencyTextView>(R.id.fiat_value) }
    private val date by lazy { containerView.findViewById<TextView>(R.id.transaction_date_and_time) }
    private val statusContainer by lazy { containerView.findViewById<View>(R.id.status_layout) }
    private val primaryStatusTxt by lazy { containerView.findViewById<TextView>(R.id.transaction_primary_status) }
    private val secondaryStatusTxt by lazy { containerView.findViewById<TextView>(R.id.transaction_secondary_status) }
    private val inputsLabel by lazy { containerView.findViewById<TextView>(R.id.input_addresses_label) }
    private val inputsContainer by lazy { containerView.findViewById<View>(R.id.inputs_container) }
    private val inputsContainerWrapper by lazy { containerView.findViewById<View>(R.id.inputs_container_content) }
    private val inputsAddressesContainer by lazy {
        containerView.findViewById<ViewGroup>(R.id.transaction_input_addresses_container)
    }
    private val outputsLabel by lazy { containerView.findViewById<TextView>(R.id.output_addresses_label) }
    private val outputsContainer by lazy { containerView.findViewById<View>(R.id.outputs_container) }
    private val outputsContainerWrapper by lazy { containerView.findViewById<View>(R.id.outputs_container_content) }
    private val outputsAddressesContainer by lazy {
        containerView.findViewById<ViewGroup>(R.id.transaction_output_addresses_container)
    }
    private val feeRow by lazy { containerView.findViewById<View>(R.id.fee_container) }
    private val paymentMemo by lazy { containerView.findViewById<TextView>(R.id.payment_memo) }
    private val paymentMemoContainer by lazy { containerView.findViewById<View>(R.id.payment_memo_container) }
    private val payeeSecuredByContainer by lazy { containerView.findViewById<View>(R.id.payee_verified_by_container) }
    private val payeeSecuredBy by lazy { containerView.findViewById<TextView>(R.id.payee_secured_by) }

    fun bind(tx: Transaction, payeeName: String? = null, payeeSecuredBy: String? = null) {
        val noCodeFormat = WalletApplication.getInstance().configuration.format.noCode()
        val wallet = WalletApplication.getInstance().wallet
        val primaryStatus = TransactionUtil.getTransactionTypeName(tx, wallet)
        val secondaryStatus = TransactionUtil.getReceivedStatusString(tx, wallet)
        val errorStatus = TransactionUtil.getErrorName(tx)
        var primaryStatusStr = if (tx.type != Transaction.Type.TRANSACTION_NORMAL || tx.isCoinBase) {
            ctx.getString(primaryStatus)
        } else {
            ""
        }
        var secondaryStatusStr = if (secondaryStatus != -1) {
            ctx.getString(secondaryStatus)
        } else {
            ""
        }
        val errorStatusStr = if (errorStatus != -1) {
            ctx.getString(errorStatus)
        } else {
            ""
        }

        if (payeeName != null) {
            this.paymentMemo.text = payeeName
            this.paymentMemoContainer.visibility = View.VISIBLE
            this.payeeSecuredBy.text = payeeSecuredBy
            payeeSecuredByContainer.visibility = View.VISIBLE
            outputsContainer.visibility = View.GONE
            inputsContainer.visibility = View.GONE
            statusContainer.visibility = View.GONE
            this.paymentMemoContainer.setOnClickListener {
                outputsContainer.visibility = if (outputsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                inputsContainer.visibility = outputsContainer.visibility
            }
        }

        // handle sending
        if (TransactionUtil.isSending(tx, wallet)) {
            primaryStatusStr = ctx.getString(R.string.transaction_row_status_sending)
            secondaryStatusStr = ""
        }

        //Address List
        val inputAddresses: List<Address>
        val outputAddresses: List<Address>

        if (tx.isOutgoing()) {
            inputAddresses = WalletUtils.getFromAddressOfSent(tx, wallet)
            outputAddresses = if (tx.isEntirelySelf) {
                inputsLabel.setText(R.string.transaction_details_moved_from)
                outputsLabel.setText(R.string.transaction_details_moved_internally_to)
                tx.allOutputAddresses
            } else {
                outputsLabel.setText(R.string.transaction_details_sent_to)
                WalletUtils.getToAddressOfSent(tx, wallet)
            }
        } else {
            inputAddresses = arrayListOf()
            outputAddresses = WalletUtils.getToAddressOfReceived(tx, wallet)
            outputsLabel.setText(R.string.transaction_details_received_at)
        }

        val inflater = LayoutInflater.from(containerView.context)
        inputsContainerWrapper.visibility = if (inputAddresses.isEmpty()) View.GONE else View.VISIBLE
        inputAddresses.forEach {
            val addressView = inflater.inflate(R.layout.transaction_result_address_row,
                    inputsAddressesContainer, false) as TextView
            addressView.text = it.toBase58()
            inputsAddressesContainer.addView(addressView)
        }
        outputsContainerWrapper.visibility = if (outputAddresses.isEmpty()) View.GONE else View.VISIBLE
        outputAddresses.forEach {
            val addressView = inflater.inflate(R.layout.transaction_result_address_row,
                    outputsAddressesContainer, false) as TextView
            addressView.text = it.toBase58()
            outputsAddressesContainer.addView(addressView)
        }

        dashAmount.setFormat(noCodeFormat)
        //For displaying purposes only
        if (tx.value!!.isNegative) {
            dashAmount.setAmount(tx.value!!.negate())
        } else {
            dashAmount.setAmount(tx.value)
        }

        transactionFee.setFormat(noCodeFormat)
        transactionFee.setAmount(tx.fee)

        date.text = DateUtils.formatDateTime(containerView.context, tx.updateTime.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)

        val exchangeRate = tx.exchangeRate
        if (exchangeRate != null) {
            fiatValue.setFiatAmount(tx.value, exchangeRate, Constants.LOCAL_FORMAT,
                    exchangeRate?.fiat?.currencySymbol)
        } else {
            fiatValue.setText(R.string.transaction_row_rate_not_available)
        }

        // transaction status
        if (errorStatusStr.isNotEmpty()) {
            //set colors to red
            val errorColor = ContextCompat.getColor(ctx, R.color.fg_error)
            primaryStatusTxt.setTextColor(errorColor)
            secondaryStatusTxt.setTextColor(errorColor)
            primaryStatusTxt.text = ctx.getString(R.string.transaction_row_status_error_sending)
            secondaryStatusTxt.text = errorStatusStr
        } else {
            if (primaryStatusStr.isNotEmpty()) {
                primaryStatusTxt.text = primaryStatusStr
            } else {
                primaryStatusTxt.visibility = View.GONE
            }
            if (secondaryStatusStr.isNotEmpty()) {
                secondaryStatusTxt.text = secondaryStatusStr
            } else {
                secondaryStatusTxt.visibility = View.GONE
            }
        }

        setTransactionDirection(tx)
    }

    private fun setTransactionDirection(tx: Transaction) {
        val dashAmountTextColor: Int
        if (tx.isOutgoing()) {
            checkIcon.setImageResource(R.drawable.ic_transaction_sent)
            transactionTitle.text = ctx.getText(R.string.transaction_details_amount_sent)
            transactionAmountSignal.text = "-"
            dashAmountTextColor = ContextCompat.getColor(ctx, android.R.color.black)
        } else {
            checkIcon.setImageResource(R.drawable.ic_transaction_received)
            transactionTitle.text = ctx.getText(R.string.transaction_details_amount_received)
            transactionAmountSignal.text = "+"
            dashAmountTextColor = ContextCompat.getColor(ctx, R.color.colorPrimary)
        }

        feeRow.visibility = if (tx.fee != null && tx.fee.isPositive) View.VISIBLE else View.GONE

        transactionAmountSignal.setTextColor(dashAmountTextColor)
        dashAmountSymbol.setColorFilter(dashAmountTextColor)
        dashAmount.setTextColor(dashAmountTextColor)

        checkIcon.visibility = View.VISIBLE
        transactionAmountSignal.visibility = View.VISIBLE
    }

}