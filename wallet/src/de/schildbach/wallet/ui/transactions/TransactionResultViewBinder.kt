/*
 * Copyright 2019 Dash Core Group.
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

package de.schildbach.wallet.ui.transactions

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.*
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.allOutputAddresses
import org.dash.wallet.common.ui.CurrencyTextView

/**
 * @author Samuel Barbosa
 */
@ExperimentalCoroutinesApi
class TransactionResultViewBinder(
    private val wallet: Wallet,
    private val dashFormat: MonetaryFormat,
    private val containerView: View
): TransactionConfidence.Listener {
    private val ctx by lazy { containerView.context }
    private val checkIcon by lazy { containerView.findViewById<ImageView>(R.id.check_icon) }
    private val secondaryIcon by lazy { containerView.findViewById<ImageView>(R.id.secondary_icon) }
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
    private val errorContainer by lazy { containerView.findViewById<View>(R.id.error_container) }
    private val errorDescription by lazy { containerView.findViewById<TextView>(R.id.error_description) }
    private val taxCategory by lazy { containerView.findViewById<TextView>(R.id.tax_category) }

    private val reportIssueContainer by lazy { containerView.findViewById<View>(R.id.report_issue_card) }
    private val privateMemoContainer by lazy { containerView.findViewById<View>(R.id.private_memo) }
    private val addPrivateMemoBtn by lazy { containerView.findViewById<Button>(R.id.add_private_memo_btn) }
    private val privateMemoText by lazy { containerView.findViewById<TextView>(R.id.private_memo_text) }
    private val dateContainer by lazy { containerView.findViewById<View>(R.id.date_container) }
    private val explorerContainer by lazy { containerView.findViewById<View>(R.id.open_explorer_card) }

    private val resourceMapper = TxResourceMapper()
    private lateinit var transaction: Transaction
    private var dashPayProfile: DashPayProfile? = null

    fun bind(tx: Transaction, profile: DashPayProfile?, payeeName: String? = null, payeeSecuredBy: String? = null) {
        this.transaction = tx
        this.dashPayProfile = profile

        val value = tx.getValue(wallet)
        val isSent = value.signum() < 0

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

        updateStatus()

        //Address List
        val inputAddresses: List<Address>
        val outputAddresses: List<Address>

        if (isSent) {
            inputAddresses = TransactionUtils.getFromAddressOfSent(tx)
            outputAddresses = if (TransactionUtils.isEntirelySelf(tx, wallet)) {
                inputsLabel.setText(R.string.transaction_details_moved_from)
                outputsLabel.setText(R.string.transaction_details_moved_internally_to)
                tx.allOutputAddresses
            } else {
                outputsLabel.setText(R.string.transaction_details_sent_to)
                TransactionUtils.getToAddressOfSent(tx, wallet)
            }
        } else {
            inputAddresses = arrayListOf()
            outputAddresses = TransactionUtils.getToAddressOfReceived(tx, wallet)
            inputsLabel.setText(R.string.transaction_details_received_from)
            outputsLabel.setText(R.string.transaction_details_received_at)
        }

        val inflater = LayoutInflater.from(containerView.context)

        if (profile != null && !TransactionUtils.isEntirelySelf(transaction, wallet)) {
            ProfilePictureDisplay.display(checkIcon, profile)
            outputsContainer.isVisible = true

            val userNameView = inflater.inflate(R.layout.transaction_result_address_row,
                outputsAddressesContainer, false) as TextView
            userNameView.text = profile.username

            if (profile.displayName.isNotEmpty()) {
                val displayNameView = inflater.inflate(R.layout.transaction_result_address_row,
                    outputsAddressesContainer, false) as TextView
                displayNameView.text = profile.displayName
                userNameView.setTextColor(R.color.content_secondary)

                if (isSent) {
                    outputsAddressesContainer.addView(displayNameView)
                } else {
                    inputsAddressesContainer.addView(displayNameView)
                }
            }

            if (isSent) {
                outputsAddressesContainer.addView(userNameView)
                outputsAddressesContainer.setOnClickListener { openProfile(profile) }
                setInputs(inputAddresses, inflater)
            } else {
                inputsAddressesContainer.addView(userNameView)
                inputsAddressesContainer.setOnClickListener { openProfile(profile) }
                setOutputs(outputAddresses, inflater)
            }

            checkIcon.setOnClickListener { openProfile(profile) }
        } else {
            setInputs(inputAddresses, inflater)
            setOutputs(outputAddresses, inflater)
        }

        dashAmount.setFormat(dashFormat)
        //For displaying purposes only
        if (value.isNegative) {
            dashAmount.setAmount(value.negate())
        } else {
            dashAmount.setAmount(value)
        }

        if (isFeeAvailable(tx.fee)) {
            transactionFee.setFormat(dashFormat)
            transactionFee.setAmount(tx.fee)
        }

        date.text = DateUtils.formatDateTime(containerView.context, tx.updateTime.time,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)

        val exchangeRate = tx.exchangeRate
        if (exchangeRate != null) {
            fiatValue.setFiatAmount(tx.getValue(wallet), exchangeRate, Constants.LOCAL_FORMAT,
                exchangeRate.fiat?.currencySymbol)
        } else {
            fiatValue.isVisible = false
        }
    }

    override fun onConfidenceChanged(
        confidence: TransactionConfidence?,
        reason: TransactionConfidence.Listener.ChangeReason?
    ) {
        org.bitcoinj.core.Context.propagate(wallet.context)
        updateStatus(true)
    }

    private fun updateStatus(fromConfidence: Boolean = false) {
        val primaryStatus = resourceMapper.getTransactionTypeName(transaction, wallet)
        val secondaryStatus = resourceMapper.getReceivedStatusString(transaction, wallet.context)
        val errorStatus = resourceMapper.getErrorName(transaction)
        var primaryStatusStr = if (transaction.type != Transaction.Type.TRANSACTION_NORMAL || transaction.isCoinBase) {
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

        // handle sending
        if (resourceMapper.isSending(transaction, wallet)) {
            primaryStatusStr = ctx.getString(R.string.transaction_row_status_sending)
            secondaryStatusStr = ""
        }

        setTransactionDirection(errorStatusStr, fromConfidence)
    }

    private fun setTransactionDirection(errorStatusStr: String, fromConfidence: Boolean) {
        @DrawableRes val imageResource: Int

        if (errorStatusStr.isNotEmpty()) {
            if (dashPayProfile != null) {
                secondaryIcon.isVisible = true
                secondaryIcon.setImageResource(R.drawable.ic_transaction_failed)
            } else {
                checkIcon.setImageResource(R.drawable.ic_transaction_failed)
            }

            errorContainer.isVisible = true
            reportIssueContainer.isVisible = true
            privateMemoContainer.isVisible = false
            outputsContainer.isVisible = false
            inputsContainer.isVisible = false
            feeRow.isVisible = false
            dateContainer.isVisible = false
            explorerContainer.isVisible = false
            transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.content_warning))
            transactionTitle.text = ctx.getText(R.string.transaction_failed_details)
            errorDescription.text = errorStatusStr
            transactionAmountSignal.text = "-"
        } else {
            if (transaction.getValue(wallet).signum() < 0) {
                imageResource = if (TransactionUtils.isEntirelySelf(transaction, wallet)) {
                    R.drawable.ic_shuffle
                } else {
                    R.drawable.ic_transaction_sent
                }

                transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.dash_blue))
                transactionTitle.text = ctx.getText(R.string.transaction_details_amount_sent)
                transactionAmountSignal.text = "-"
                transactionAmountSignal.isVisible = true
            } else {
                imageResource = R.drawable.ic_transaction_received
                transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.system_green))
                transactionTitle.text = ctx.getText(R.string.transaction_details_amount_received)
                transactionAmountSignal.isVisible = true
                transactionAmountSignal.text = "+"
            }
            checkIcon.isVisible = true

            if (!fromConfidence) {
                // If it's a confidence update, not need to set the send/receive icons again.
                // Some hosts are replacing those with custom animated ones.
                if (dashPayProfile != null) {
                    secondaryIcon.isVisible = true
                    secondaryIcon.setImageResource(imageResource)
                } else {
                    checkIcon.setImageResource(imageResource)
                }
            }
        }

        feeRow.visibility = if (isFeeAvailable(transaction.fee)) View.VISIBLE else View.GONE
    }

    fun setTransactionMetadata(transactionMetadata: TransactionMetadata) {
        val categories =
            containerView.resources.getStringArray(R.array.transaction_result_tax_categories)

        if (transactionMetadata.taxCategory != null) {
            taxCategory.text = categories[transactionMetadata.taxCategory!!.value]
        } else {
            taxCategory.text = categories[transactionMetadata.defaultTaxCategory.value]
        }

        privateMemoText.text = transactionMetadata.memo

        if (transactionMetadata.memo.isNotEmpty()) {
            privateMemoText.isVisible = true
            addPrivateMemoBtn.setText(R.string.edit_note)
        } else {
            privateMemoText.isVisible = false
            addPrivateMemoBtn.setText(R.string.add_note)
        }
    }

    private fun isFeeAvailable(transactionFee: Coin?): Boolean {
        return transactionFee != null && transactionFee.isPositive
    }

    private fun openProfile(profile: DashPayProfile) {
        ctx.startActivity(DashPayUserActivity.createIntent(ctx, profile))
    }

    private fun setInputs(inputAddresses: List<Address>, inflater: LayoutInflater) {
        inputsContainer.isVisible = inputAddresses.isNotEmpty()
        inputAddresses.forEach {
            val addressView = inflater.inflate(R.layout.transaction_result_address_row,
                inputsAddressesContainer, false) as TextView
            addressView.text = it.toBase58()
            inputsAddressesContainer.addView(addressView)
        }
    }

    private fun setOutputs(outputAddresses: List<Address>, inflater: LayoutInflater) {
        outputsContainer.isVisible = outputAddresses.isNotEmpty()
        outputAddresses.forEach {
            val addressView = inflater.inflate(R.layout.transaction_result_address_row,
                outputsAddressesContainer, false) as TextView
            addressView.text = it.toBase58()
            outputsAddressesContainer.addView(addressView)
        }
    }
}