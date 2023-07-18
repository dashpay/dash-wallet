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

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.load
import coil.transform.RoundedCornersTransformation
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
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
import org.dash.wallet.common.util.currencySymbol
import org.dash.wallet.common.util.makeLinks

/**
 * @author Samuel Barbosa
 */
class TransactionResultViewBinder(
    private val wallet: Wallet,
    private val dashFormat: MonetaryFormat,
    private val binding: TransactionResultContentBinding
) {
    private val iconSize = binding.root.context.resources.getDimensionPixelSize(R.dimen.transaction_details_icon_size)
    private val context by lazy { binding.root.context }
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
            binding.paymentMemo.text = payeeName
            binding.paymentMemoContainer.visibility = View.VISIBLE
            binding.payeeSecuredBy.text = payeeSecuredBy
            binding.payeeVerifiedByContainer.visibility = View.VISIBLE
            binding.outputsContainer.visibility = View.GONE
            binding.inputsContainer.visibility = View.GONE
            binding.paymentMemoContainer.setOnClickListener {
                binding.outputsContainer.isVisible = !binding.outputsContainer.isVisible
                binding.inputsContainer.isVisible = binding.outputsContainer.isVisible
            }
        }

        updateStatus()

        // Address List
        val inputAddresses: List<Address>
        val outputAddresses: List<Address>

        if (isSent) {
            inputAddresses = TransactionUtils.getFromAddressOfSent(tx)
            outputAddresses = if (tx.isEntirelySelf(wallet)) {
                binding.inputAddressesLabel.setText(R.string.transaction_details_moved_from)
                binding.outputAddressesLabel.setText(R.string.transaction_details_moved_internally_to)
                tx.allOutputAddresses
            } else {
                binding.outputAddressesLabel.setText(R.string.transaction_details_sent_to)
                TransactionUtils.getToAddressOfSent(tx, wallet)
            }
        } else {
            inputAddresses = arrayListOf()
            outputAddresses = TransactionUtils.getToAddressOfReceived(tx, wallet)
            binding.outputAddressesLabel.setText(R.string.transaction_details_received_at)
        }

        val inflater = LayoutInflater.from(context)
        setInputs(inputAddresses, inflater)
        setOutputs(outputAddresses, inflater)
        binding.dashAmount.setFormat(dashFormat)

        // For displaying purposes only
        if (value.isNegative) {
            binding.dashAmount.setAmount(value.negate())
        } else {
            binding.dashAmount.setAmount(value)
        }

        if (isFeeAvailable(tx.fee)) {
            binding.transactionFee.setFormat(dashFormat)
            binding.transactionFee.setAmount(tx.fee)
        }

        binding.transactionDateAndTime.text = DateUtils.formatDateTime(
            context,
            tx.updateTime.time,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )

        val exchangeRate = tx.exchangeRate
        if (exchangeRate != null) {
            binding.fiatValue.setFiatAmount(
                tx.getValue(wallet),
                exchangeRate,
                Constants.LOCAL_FORMAT,
                exchangeRate.fiat?.currencySymbol
            )
        } else {
            binding.fiatValue.isVisible = false
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
        binding.taxCategory.text = context.getString(strResource!!)

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
        binding.transactionTitle.text = customTitle
        binding.transactionTitle.setTextColor(ContextCompat.getColor(context, R.color.content_primary))
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
            binding.checkIcon.setImageResource(iconRes)
            binding.secondaryIcon.isVisible = false

            if (binding.checkIcon.drawable is Animatable) {
                binding.checkIcon.isVisible = false
                binding.checkIcon.postDelayed({
                    binding.checkIcon.isVisible = true
                    (binding.checkIcon.drawable as Animatable).start()
                }, 300)
            }
        } else {
            binding.checkIcon.load(iconBitmap) {
                transformations(RoundedCornersTransformation(iconSize * 2.toFloat()))
            }
            binding.secondaryIcon.isVisible = true
            binding.secondaryIcon.setImageResource(iconRes)
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

            binding.errorContainer.isVisible = true
            binding.reportIssueCard.isVisible = showReportIssue
            binding.outputsContainer.isVisible = false
            binding.inputsContainer.isVisible = false
            binding.feeContainer.isVisible = false
            binding.dateContainer.isVisible = false
            binding.openExplorerCard.isVisible = false
            binding.openTaxCategoryCard.isVisible = false
            binding.dashAmount.setStrikeThru(true)
            binding.fiatValue.setStrikeThru(true)
            binding.checkIcon.setImageResource(R.drawable.ic_transaction_failed)
            binding.transactionTitle.text = context.getText(R.string.transaction_failed_details)

            var rescanText = ""
            val additionalInfo = if (shouldSuggestRescan) {
                rescanText = context.getString(R.string.transaction_failed_rescan)
                "・${context.getString(R.string.transaction_failed_resolve)} $rescanText"
            } else if (errorStatus == TxError.InConflict) {
                "・${context.getString(R.string.transaction_failed_in_conflict)}"
            } else {
                ""
            }
            binding.errorDescription.text = context.getString(resourceMapper.getErrorName(errorStatus)) + additionalInfo

            if (shouldSuggestRescan) {
                binding.errorDescription.makeLinks(
                    Pair(
                        rescanText,
                        View.OnClickListener { onRescanTriggered?.invoke() }
                    ),
                    linkColor = R.color.dash_blue
                )
            }
        } else {
            if (tx.getValue(wallet).signum() < 0) {
                binding.checkIcon.setImageResource(
                    if (tx.isEntirelySelf(wallet)) {
                        R.drawable.ic_internal
                    } else {
                        R.drawable.ic_transaction_sent
                    }
                )

                binding.transactionTitle.setTextColor(ContextCompat.getColor(context, R.color.dash_blue))
                binding.transactionTitle.text = context.getText(R.string.transaction_details_amount_sent)
                binding.transactionAmountSignal.text = "-"
                binding.transactionAmountSignal.isVisible = true
            } else {
                binding.checkIcon.setImageResource(R.drawable.ic_transaction_received)
                binding.transactionTitle.setTextColor(ContextCompat.getColor(context, R.color.system_green))
                binding.transactionTitle.text = context.getText(R.string.transaction_details_amount_received)
                binding.transactionAmountSignal.isVisible = true
                binding.transactionAmountSignal.text = "+"
            }
            binding.checkIcon.isVisible = true
            binding.feeContainer.isVisible = isFeeAvailable(tx.fee)
        }
    }

    fun setOnRescanTriggered(listener: () -> Unit) {
        onRescanTriggered = listener
    }

    private fun isFeeAvailable(transactionFee: Coin?): Boolean {
        return transactionFee != null && transactionFee.isPositive
    }

    private fun setInputs(inputAddresses: List<Address>, inflater: LayoutInflater) {
        binding.inputsContainer.isVisible = inputAddresses.isNotEmpty()
        inputAddresses.forEach {
            val addressView = inflater.inflate(
                R.layout.transaction_result_address_row,
                binding.transactionInputAddressesContainer,
                false
            ) as TextView
            addressView.text = it.toBase58()
            binding.transactionInputAddressesContainer.addView(addressView)
        }
    }

    private fun setOutputs(outputAddresses: List<Address>, inflater: LayoutInflater) {
        binding.outputsContainer.isVisible = outputAddresses.isNotEmpty()
        outputAddresses.forEach {
            val addressView = inflater.inflate(
                R.layout.transaction_result_address_row,
                binding.transactionOutputAddressesContainer,
                false
            ) as TextView
            addressView.text = it.toBase58()
            binding.transactionOutputAddressesContainer.addView(addressView)
        }
    }
}
