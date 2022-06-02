/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.main

import android.content.res.Resources
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.TransactionUtil
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionRowExtBinding
import org.bitcoinj.core.*
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Constants
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet


class TransactionsAdapter(
    private val wallet: Wallet,
    private val resources: Resources,
    private val clickListener: (TransactionWrapper, Int) -> Unit
) : ListAdapter<TransactionWrapper, TransactionsAdapter.TransactionViewHolder>(DiffCallback()) {
    private val format = WalletApplication.getInstance().configuration.format // TODO
    private val colorPrimaryStatus = resources.getColor(R.color.primary_status, null)
    private val colorSecondaryStatus = resources.getColor(R.color.secondary_status, null)
    private val contentColor = resources.getColor(R.color.content_primary, null)
    private val warningColor = resources.getColor(R.color.content_warning, null)
    private val transactionCache = hashMapOf<Sha256Hash, TransactionCacheEntry>()


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = TransactionRowExtBinding.inflate(inflater, parent, false)

        return TransactionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val tx = getItem(position)
        holder.bind(tx, wallet)

        holder.itemView.setOnClickListener {
            clickListener.invoke(tx, position)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TransactionWrapper>() {
        override fun areItemsTheSame(oldItem: TransactionWrapper, newItem: TransactionWrapper): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: TransactionWrapper, newItem: TransactionWrapper): Boolean {
            return oldItem.transactions.first().txId == newItem.transactions.first().txId
        }
    }

    inner class TransactionViewHolder(
        private val binding: TransactionRowExtBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.fiatView.setApplyMarkup(false)
        }

        fun bind(wrapper: TransactionWrapper, wallet: Wallet) {
            if (wrapper is FullCrowdNodeSignUpTxSet) {
                val value = wrapper.getValue(wallet)

                // TODO
            } else {
                val tx = wrapper.transactions.first()
                val fee = tx.fee
                val confidence = tx.confidence

                var txCache = transactionCache[tx.txId]

                if (txCache == null) {
                    val value: Coin = tx.getValue(wallet)
                    val isSent = value.signum() < 0
                    val showFee = isSent && fee != null && !fee.isZero

                    txCache = TransactionCacheEntry(
                        value, isSent, showFee
                    )
                    transactionCache[tx.txId] = txCache
                }

                // Assign the colors of text and values
                val primaryStatusColor: Int
                val secondaryStatusColor: Int
                val valueColor: Int

                if (confidence.hasErrors()) {
                    primaryStatusColor = warningColor
                    secondaryStatusColor = warningColor
                    valueColor = warningColor
                } else {
                    primaryStatusColor = colorPrimaryStatus
                    secondaryStatusColor = colorSecondaryStatus
                    valueColor = contentColor
                }

                // Set the time. eg.  "<date> <time>"
                val time = tx.updateTime
                binding.time.text = DateUtils.formatDateTime(
                    itemView.context, time.time,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
                )

                // Set primary status - Sent:  Sent, Masternode Special Tx's, Internal
                //                      Received:  Received, Mining Rewards, Masternode Rewards
                val idPrimaryStatus = TransactionUtil.getTransactionTypeName(tx, wallet)
                binding.primaryStatus.setText(idPrimaryStatus)
                binding.primaryStatus.setTextColor(primaryStatusColor)

                // Set the value.  [signal] D [value]
                // signal is + or -, or not visible if the value is zero (internal or other special transactions)
                // D is the Dash Symbol
                // value has no sign.  It is zero for internal or other special transactions
                binding.value.setFormat(format)

                val value = if (txCache.showFee) txCache.value.add(fee) else txCache.value

                binding.signal.isVisible = !value.isZero
                binding.value.setTextColor(valueColor)
                binding.signal.setTextColor(valueColor)
                binding.dashAmountSymbol.setColorFilter(valueColor)

                if (value.isPositive) {
                    binding.signal.text = String.format("%c", Constants.CURRENCY_PLUS_SIGN)
                    binding.value.setAmount(value)
                } else if (value.isNegative) {
                    binding.signal.text = String.format("%c", Constants.CURRENCY_MINUS_SIGN)
                    binding.value.setAmount(value.negate())
                } else {
                    binding.value.setAmount(Coin.ZERO)
                }

                // fiat value
                if (!value.isZero) {
                    val exchangeRate = tx.exchangeRate

                    if (exchangeRate != null) {
                        val exchangeCurrencyCode = GenericUtils.currencySymbol(exchangeRate.fiat.currencyCode)
                        binding.fiatView.setFiatAmount(
                            value, exchangeRate, de.schildbach.wallet.Constants.LOCAL_FORMAT,
                            exchangeCurrencyCode
                        )
                        binding.fiatView.isVisible = true
                        binding.rateNotAvailable.isVisible = false
                    } else {
                        binding.fiatView.isVisible = false
                        binding.rateNotAvailable.isVisible = true
                    }
                } else {
                    binding.fiatView.isVisible = false
                    binding.rateNotAvailable.isVisible = false
                }

                // Show the secondary status:
                var secondaryStatusId: Int = -1

                if (confidence.hasErrors()) {
                    secondaryStatusId = TransactionUtil.getErrorName(tx)
                } else if (!txCache.isSent) {
                    secondaryStatusId = TransactionUtil.getReceivedStatusString(tx, wallet)
                }

                if (secondaryStatusId != -1) {
                    binding.secondaryStatus.setText(secondaryStatusId)
                } else {
                    binding.secondaryStatus.text = null
                }
                binding.secondaryStatus.setTextColor(secondaryStatusColor)
            }
        }
    }

    internal data class TransactionCacheEntry(
        val value: Coin,
        val isSent: Boolean,
        val showFee: Boolean
    )
}