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

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.annotation.SuppressLint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.load
import coil.transform.RoundedCornersTransformation
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionUtils.allOutputAddresses
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.ui.CurrencyTextView
import org.dash.wallet.common.util.currencySymbol
import org.dash.wallet.common.util.makeLinks

/**
 * @author Samuel Barbosa
 */
class TransactionResultViewBinder(
    private val wallet: Wallet,
    private val dashFormat: MonetaryFormat,
    private val containerView: View
) {
    private val iconSize = containerView.resources.getDimensionPixelSize(R.dimen.transaction_details_icon_size)
    private val ctx by lazy { containerView.context }
    private val checkIcon by lazy { containerView.findViewById<ImageView>(R.id.check_icon) }
    private val secondaryIcon by lazy { containerView.findViewById<ImageView>(R.id.secondary_icon) }
    private val transactionAmountSignal by lazy { containerView.findViewById<TextView>(R.id.transaction_amount_signal) }
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
    private val taxCategoryCard by lazy { containerView.findViewById<View>(R.id.open_tax_category_card) }
    private val reportIssueContainer by lazy { containerView.findViewById<View>(R.id.report_issue_card) }
    private val dateContainer by lazy { containerView.findViewById<View>(R.id.date_container) }
    private val explorerContainer by lazy { containerView.findViewById<View>(R.id.open_explorer_card) }

    private val resourceMapper = TxResourceMapper()
    private val taxCategoryNames = mapOf(
        TaxCategory.Income to R.string.tax_category_income,
        TaxCategory.Expense to R.string.tax_category_expense,
        TaxCategory.TransferIn to R.string.tax_category_transfer_in,
        TaxCategory.TransferOut to R.string.tax_category_transfer_out
    )
    private var onRescanTriggered: (() -> Unit)? = null

    private lateinit var transaction: Transaction
    private var isError = false
    private var iconBitmap: Bitmap? = null
    @DrawableRes
    private var iconRes: Int? = null
    private var customTitle: String? = null

    fun bind(tx: Transaction, payeeName: String? = null, payeeSecuredBy: String? = null) {
        this.transaction = tx

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

        // Address List
        val inputAddresses: List<Address>
        val outputAddresses: List<Address>

        if (isSent) {
            inputAddresses = TransactionUtils.getFromAddressOfSent(tx)
            outputAddresses = if (tx.isEntirelySelf(wallet)) {
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
            outputsLabel.setText(R.string.transaction_details_received_at)
        }

        val inflater = LayoutInflater.from(containerView.context)
        setInputs(inputAddresses, inflater)
        setOutputs(outputAddresses, inflater)

        dashAmount.setFormat(dashFormat)
        // For displaying purposes only
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

    private fun updateStatus(fromConfidence: Boolean = false) {
        if (!fromConfidence || isError) {
            // If it's a confidence update, not need to set the send/receive icons again.
            // Some hosts are replacing those with custom animated ones.
            updateIcon()
        }

        setTransactionDirection(transaction, wallet)
    }


    fun setTransactionMetadata(transactionMetadata: TransactionMetadata) {
        val strResource = if (transactionMetadata.taxCategory != null) {
            taxCategoryNames[transactionMetadata.taxCategory!!]
        } else {
            taxCategoryNames[transactionMetadata.defaultTaxCategory]
        }
        taxCategory.text = containerView.resources.getString(strResource!!)

        if (transactionMetadata.service == ServiceName.DashDirect) {
            iconRes = R.drawable.ic_gift_card_tx
        }

        updateIcon()
    }

    fun setTransactionIcon(bitmap: Bitmap) {
        iconBitmap = bitmap
        updateIcon()
    }

    fun setTransactionIcon(@DrawableRes drawableRes: Int) {
        iconRes = drawableRes
        updateIcon()
    }

    fun setCustomTitle(title: String) {
        customTitle = title
        transactionTitle.text = customTitle
        transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.content_primary))
    }

    private fun updateIcon() {
        val iconRes = if (isError) {
            R.drawable.ic_transaction_failed
        } else if (iconRes != null) {
            iconRes!!
        } else if (transaction.getValue(wallet).signum() >= 0) {
            R.drawable.ic_transaction_received
        } else if (transaction.isEntirelySelf(wallet)) {
            R.drawable.ic_internal
        } else {
            R.drawable.ic_transaction_sent
        }

        if (iconBitmap == null) {
            checkIcon.setImageResource(iconRes)
            secondaryIcon.isVisible = false

            if (checkIcon.drawable is Animatable) {
                checkIcon.isVisible = false
                checkIcon.postDelayed({
                    checkIcon.isVisible = true
                    (checkIcon.drawable as Animatable).start()
                }, 300)
            }
        } else {
            checkIcon.load(iconBitmap) {
                transformations(RoundedCornersTransformation(iconSize * 2.toFloat()))
            }
            secondaryIcon.isVisible = true
            secondaryIcon.setImageResource(iconRes)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setTransactionDirection(tx: Transaction, wallet: Wallet) {
        if (tx.confidence.hasErrors()) {
            val errorStatus = TxError.fromTransaction(tx)
            val showReportIssue = errorStatus == TxError.DoubleSpend || errorStatus == TxError.Duplicate ||
                errorStatus == TxError.Unknown || errorStatus == TxError.InConflict
            val shouldSuggestRescan = errorStatus == TxError.DoubleSpend || errorStatus == TxError.Duplicate ||
                errorStatus == TxError.Unknown

            errorContainer.isVisible = true
            reportIssueContainer.isVisible = showReportIssue
            outputsContainer.isVisible = false
            inputsContainer.isVisible = false
            feeRow.isVisible = false
            dateContainer.isVisible = false
            explorerContainer.isVisible = false
            taxCategoryCard.isVisible = false
            dashAmount.setStrikeThru(true)
            fiatValue.setStrikeThru(true)
            checkIcon.setImageResource(R.drawable.ic_transaction_failed)
            transactionTitle.text = ctx.getText(R.string.transaction_failed_details)

            var rescanText = ""
            val additionalInfo = if (shouldSuggestRescan) {
                rescanText = ctx.getString(R.string.transaction_failed_rescan)
                "・${ctx.getString(R.string.transaction_failed_resolve)} $rescanText"
            } else if (errorStatus == TxError.InConflict) {
                "・${ctx.getString(R.string.transaction_failed_in_conflict)}"
            } else {
                ""
            }
            errorDescription.text = ctx.getString(resourceMapper.getErrorName(errorStatus)) + additionalInfo

            if (shouldSuggestRescan) {
                errorDescription.makeLinks(
                    Pair(
                        rescanText,
                        View.OnClickListener { onRescanTriggered?.invoke() }
                    ),
                    linkColor = R.color.dash_blue
                )
            }
        } else {
            if (tx.getValue(wallet).signum() < 0) {
                checkIcon.setImageResource(if (tx.isEntirelySelf(wallet)) {
                    R.drawable.ic_internal
                } else {
                    R.drawable.ic_transaction_sent
                })

                transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.dash_blue))
                transactionTitle.text = ctx.getText(R.string.transaction_details_amount_sent)
                transactionAmountSignal.text = "-"
                transactionAmountSignal.isVisible = true
            } else {
                checkIcon.setImageResource(R.drawable.ic_transaction_received)
                transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.system_green))
                transactionTitle.text = ctx.getText(R.string.transaction_details_amount_received)
                transactionAmountSignal.isVisible = true
                transactionAmountSignal.text = "+"
            }
            checkIcon.isVisible = true
            feeRow.isVisible = isFeeAvailable(tx.fee)
        }
    }

    private fun setTransactionDirection(errorStatusStr: String) {
        if (errorStatusStr.isNotEmpty()) {
            errorContainer.isVisible = true
            reportIssueContainer.isVisible = true
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
            transactionAmountSignal.isVisible = true
            val isSent = transaction.getValue(wallet).signum() < 0
            transactionAmountSignal.text = if (isSent) "-" else "+"

            if (customTitle == null) {
                if (isSent) {
                    transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.dash_blue))
                    transactionTitle.text = ctx.getText(R.string.transaction_details_amount_sent)
                } else {
                    transactionTitle.setTextColor(ContextCompat.getColor(ctx, R.color.system_green))
                    transactionTitle.text = ctx.getText(R.string.transaction_details_amount_received)
                }
            }
        }

        feeRow.isVisible = isFeeAvailable(transaction.fee)
    }

    fun setOnRescanTriggered(listener: () -> Unit) {
        onRescanTriggered = listener
    }

    private fun isFeeAvailable(transactionFee: Coin?): Boolean {
        return transactionFee != null && transactionFee.isPositive
    }

    private fun setInputs(inputAddresses: List<Address>, inflater: LayoutInflater) {
        inputsContainer.isVisible = inputAddresses.isNotEmpty()
        inputAddresses.forEach {
            val addressView = inflater.inflate(
                R.layout.transaction_result_address_row,
                inputsAddressesContainer,
                false
            ) as TextView
            addressView.text = it.toBase58()
            inputsAddressesContainer.addView(addressView)
        }
    }

    private fun setOutputs(outputAddresses: List<Address>, inflater: LayoutInflater) {
        outputsContainer.isVisible = outputAddresses.isNotEmpty()
        outputAddresses.forEach {
            val addressView = inflater.inflate(
                R.layout.transaction_result_address_row,
                outputsAddressesContainer,
                false
            ) as TextView
            addressView.text = it.toBase58()
            outputsAddressesContainer.addView(addressView)
        }
    }
}