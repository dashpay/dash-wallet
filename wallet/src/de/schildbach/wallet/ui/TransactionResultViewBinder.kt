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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.*
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
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
    private val inputsLabel by lazy { containerView.findViewById<TextView>(R.id.input_addresses_label) }
    private val inputsContainer by lazy { containerView.findViewById<View>(R.id.inputs_container) }
    private val inputsAddressesContainer by lazy {
        containerView.findViewById<ViewGroup>(R.id.transaction_input_addresses_container)
    }
    private val outputsLabel by lazy { containerView.findViewById<TextView>(R.id.output_addresses_label) }
    private val outputsContainer by lazy { containerView.findViewById<View>(R.id.outputs_container) }
    private val outputsAddressesContainer by lazy {
        containerView.findViewById<ViewGroup>(R.id.transaction_output_addresses_container)
    }
    private val feeRow by lazy { containerView.findViewById<View>(R.id.fee_container) }
    private val paymentMemo by lazy { containerView.findViewById<TextView>(R.id.payment_memo) }
    private val paymentMemoContainer by lazy { containerView.findViewById<View>(R.id.payment_memo_container) }
    private val payeeSecuredByContainer by lazy { containerView.findViewById<View>(R.id.payee_verified_by_container) }
    private val payeeSecuredBy by lazy { containerView.findViewById<TextView>(R.id.payee_secured_by) }
    private val closeIcon by lazy { containerView.findViewById<ImageButton>(R.id.close_btn) }
    private val errorContainer by lazy { containerView.findViewById<View>(R.id.error_container) }
    private val errorDescription by lazy { containerView.findViewById<TextView>(R.id.error_description) }

    private val reportIssueContainer by lazy { containerView.findViewById<View>(R.id.report_issue_card) }
    private val dateContainer by lazy { containerView.findViewById<View>(R.id.date_container) }
    private val explorerContainer by lazy { containerView.findViewById<View>(R.id.open_explorer_card) }

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
        inputsContainer.visibility = if (inputAddresses.isEmpty()) View.GONE else View.VISIBLE
        inputAddresses.forEach {
            val addressView = inflater.inflate(R.layout.transaction_result_address_row,
                    inputsAddressesContainer, false) as TextView
            addressView.text = it.toBase58()
            inputsAddressesContainer.addView(addressView)
        }
        outputsContainer.visibility = if (outputAddresses.isEmpty()) View.GONE else View.VISIBLE
        outputAddresses.forEach {
            val addressView = inflater.inflate(R.layout.transaction_result_address_row,
                    outputsAddressesContainer, false) as TextView
            addressView.text = it.toBase58()
            outputsAddressesContainer.addView(addressView)
        }

        if (!inputsContainer.isVisible){
            outputsContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = 0
                bottomToBottom = 0
            }
        }

        dashAmount.setFormat(noCodeFormat)
        //For displaying purposes only
        val amountSent = if (isFeeAvailable(tx)) tx.value!!.minus(tx.fee) else tx.value!!
        if (tx.value!!.isNegative) {
            dashAmount.setAmount(amountSent.negate())
        } else {
            dashAmount.setAmount(amountSent)
        }

        if (isFeeAvailable(tx)) {
            transactionFee.setFormat(noCodeFormat)
            transactionFee.setAmount(tx.fee)
        }

        date.text = DateUtils.formatDateTime(containerView.context, tx.updateTime.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)

        val exchangeRate = tx.exchangeRate
        if (exchangeRate != null) {
            fiatValue.setFiatAmount(tx.value, exchangeRate, Constants.LOCAL_FORMAT,
                    exchangeRate.fiat?.currencySymbol)
        } else {
            fiatValue.setText(R.string.transaction_row_rate_not_available)
        }


        setTransactionDirection(tx, errorStatusStr)
    }

    private fun setTransactionDirection(tx: Transaction, errorStatusStr: String) {
        if (errorStatusStr.isNotEmpty()){
            errorContainer.isVisible = true
            reportIssueContainer.isVisible = true
            outputsContainer.isVisible = false
            inputsContainer.isVisible = false
            feeRow.isVisible = false
            dateContainer.isVisible = false
            explorerContainer.isVisible = false
            checkIcon.setImageResource(R.drawable.ic_transaction_failed)
            transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.red_300))
            transactionTitle.text = ctx.getText(R.string.transaction_failed_details)
            errorDescription.text = errorStatusStr
            transactionAmountSignal.text = "-"
        } else {
            if (tx.isOutgoing()) {
                checkIcon.setImageResource(R.drawable.ic_transaction_sent)
                transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.dash_blue))
                transactionTitle.text = ctx.getText(R.string.transaction_details_amount_sent)
                transactionAmountSignal.text = "-"

            } else {
                checkIcon.setImageResource(R.drawable.ic_transaction_received)
                transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.green_300))
                transactionTitle.text = ctx.getText(R.string.transaction_details_amount_received)
                transactionAmountSignal.text = "+"
                closeIcon.isVisible = true
                transactionTitle.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = 10
                }
            }
            checkIcon.visibility = View.VISIBLE
            transactionAmountSignal.visibility = View.VISIBLE

            if (!inputsContainer.isVisible){
                outputsContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = -40
                }
            }
        }

        feeRow.visibility = if (isFeeAvailable(tx)) View.VISIBLE else View.GONE

    }

    private fun isFeeAvailable(transaction: Transaction): Boolean {
        return transaction.fee != null && transaction.fee.isPositive
    }

}