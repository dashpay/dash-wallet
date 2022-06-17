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
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.ui.transactions.TxResourceMapper
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionRowBinding
import org.bitcoinj.core.*
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.ui.getRoundedBackground
import org.dash.wallet.common.ui.getRoundedRippleBackground
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet

class TransactionWrapperAdapter(
    private val wallet: Wallet,
    dashFormat: MonetaryFormat,
    resources: Resources,
    private val clickListener: (TransactionWrapper, Int) -> Unit
) : TransactionsHolderAdapter<TransactionWrapper>(
    wallet, dashFormat, resources, DiffCallback()
) {
    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val wrapper = getItem(position)
        holder.bind(wrapper, wallet)

        holder.itemView.setOnClickListener {
            clickListener.invoke(wrapper, position)
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
}

class TransactionAdapter(
    wallet: Wallet,
    dashFormat: MonetaryFormat,
    resources: Resources,
    private val resourceMapper: TxResourceMapper = TxResourceMapper(),
    private val clickListener: (Transaction, Int) -> Unit
) : TransactionsHolderAdapter<Transaction>(
    wallet, dashFormat, resources, DiffCallback()
) {
    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val tx = getItem(position)
        holder.bind(tx, resourceMapper)

        holder.itemView.setOnClickListener {
            clickListener.invoke(tx, position)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            return oldItem.txId == newItem.txId
        }
    }
}

open class TransactionsHolderAdapter<T>(
    private val wallet: Wallet,
    private val dashFormat: MonetaryFormat,
    private val resources: Resources,
    diffCallback: DiffUtil.ItemCallback<T>
) : ListAdapter<T, TransactionsHolderAdapter<T>.TransactionViewHolder>(diffCallback) {
    private val colorSecondaryStatus = resources.getColor(R.color.secondary_status, null)
    private val contentColor = resources.getColor(R.color.content_primary, null)
    private val warningColor = resources.getColor(R.color.content_warning, null)
    private val transactionCache = hashMapOf<Sha256Hash, TransactionCacheEntry>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = TransactionRowBinding.inflate(inflater, parent, false)

        return TransactionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) { }

    inner class TransactionViewHolder(
        private val binding: TransactionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val resourceMapper = TxResourceMapper()

        init {
            binding.fiatView.setApplyMarkup(false)
        }

        fun bind(txWrapper: TransactionWrapper, wallet: Wallet) {
            binding.root.background = resources.getRoundedRippleBackground(R.style.TransactionRowBackground)

            val tx = txWrapper.transactions.first()
            Log.i("CROWDNODE", "value: $tx")

            if (txWrapper is FullCrowdNodeSignUpTxSet) {
                val value = txWrapper.getValue(wallet)
                binding.icon.setImageResource(R.drawable.ic_crowdnode_logo)
                binding.icon.background = resources.getRoundedBackground(R.style.TxNoBackground)
                binding.primaryStatus.text = resources.getString(R.string.crowdnode_account)

                setValue(value, false)
                setFiatValue(value, tx.exchangeRate)
                setTime(tx, resourceMapper.dateTimeFormat)
                setDetails(txWrapper.transactions.size)
            } else {
                bind(tx, resourceMapper)
            }
        }

        fun bind(tx: Transaction, resourceMapper: TxResourceMapper) {
            val txCache = getTxCache(tx)

            setIcon(txCache)
            setPrimaryStatus(tx, resourceMapper)
            setSecondaryStatus(tx, txCache, resourceMapper)

            val value = if (txCache.showFee) txCache.value.add(txCache.fee) else txCache.value
            setValue(value, tx.confidence.hasErrors())
            setFiatValue(value, tx.exchangeRate)
            setTime(tx, resourceMapper.dateTimeFormat)
            setDetails(1)
        }

        private fun getTxCache(tx: Transaction): TransactionCacheEntry {
            var txCache = transactionCache[tx.txId]

            if (txCache == null) {
                val value = tx.getValue(wallet)
                val isSent = value.signum() < 0
                val fee = tx.fee
                val showFee = isSent && fee != null && !fee.isZero
                val isInternal = WalletUtils.isEntirelySelf(tx, wallet)

                txCache = TransactionCacheEntry(
                    value, isSent, isInternal, fee, showFee
                )
                transactionCache[tx.txId] = txCache
            }

            return txCache
        }

        private fun setIcon(txCache: TransactionCacheEntry) {
            if (txCache.isInternal) {
                binding.icon.setImageResource(R.drawable.ic_shuffle)
                binding.icon.background = resources.getRoundedBackground(R.style.TxSentBackground)
            } else if (txCache.isSent) {
                binding.icon.setImageResource(R.drawable.ic_transaction_sent)
                binding.icon.background = resources.getRoundedBackground(R.style.TxSentBackground)
            } else {
                binding.icon.setImageResource(R.drawable.ic_transaction_received)
                binding.icon.background = resources.getRoundedBackground(R.style.TxReceivedBackground)
            }
        }

        private fun setPrimaryStatus(tx: Transaction, resourceMapper: TxResourceMapper) {
            // Set primary status - Sent:  Sent, Masternode Special Tx's, Internal
            //                      Received:  Received, Mining Rewards, Masternode Rewards
            val idPrimaryStatus = resourceMapper.getTransactionTypeName(tx, wallet)
            binding.primaryStatus.setText(idPrimaryStatus)
            binding.primaryStatus.setTextColor(if (tx.confidence.hasErrors()) {
                warningColor
            } else {
                contentColor
            })
        }

        private fun setDetails(transactionsCount: Int) {
            if (transactionsCount > 1) {
                binding.details.isVisible = true
                binding.details.text = resources.getString(R.string.transaction_count, transactionsCount)
            } else {
                binding.details.isVisible = false
            }
        }

        private fun setTime(tx: Transaction, dateTimeFormat: Int) {
            // Set the time. eg.  "<date> <time>"
            binding.time.text = DateUtils.formatDateTime(
                itemView.context, tx.updateTime.time,
                dateTimeFormat
            )
        }

        private fun setSecondaryStatus(
            tx: Transaction,
            txCache: TransactionCacheEntry,
            resourceMapper: TxResourceMapper
        ) {
            var secondaryStatusId: Int = -1

            if (tx.confidence.hasErrors()) {
                secondaryStatusId = resourceMapper.getErrorName(tx)
            } else if (!txCache.isSent) {
                secondaryStatusId = resourceMapper.getReceivedStatusString(tx, wallet.context)
            }

            if (secondaryStatusId != -1) {
                binding.secondaryStatus.setText(secondaryStatusId)
            } else {
                binding.secondaryStatus.text = null
            }

            binding.secondaryStatus.setTextColor(if (tx.confidence.hasErrors()) {
                warningColor
            } else {
                colorSecondaryStatus
            })
        }

        private fun setValue(value: Coin, hasErrors: Boolean) {
            // Set the value.  [signal] D [value]
            // signal is + or -, or not visible if the value is zero (internal or other special transactions)
            // D is the Dash Symbol
            // value has no sign.  It is zero for internal or other special transactions
            binding.value.setFormat(dashFormat)

            val valueColor = if (hasErrors) {
                warningColor
            } else {
                contentColor
            }

            binding.signal.isVisible = !value.isZero
            binding.value.setTextColor(valueColor)
            binding.signal.setTextColor(valueColor)
            binding.dashAmountSymbol.setColorFilter(valueColor)

            if (value.isPositive) {
                binding.signal.text = "+"
                binding.value.setAmount(value)
            } else if (value.isNegative) {
                binding.signal.text = "−"
                binding.value.setAmount(value.negate())
            } else {
                binding.value.setAmount(Coin.ZERO)
            }
        }

        private fun setFiatValue(value: Coin, exchangeRate: ExchangeRate?) {
            // fiat value
            if (!value.isZero) {
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
        }
    }

    internal data class TransactionCacheEntry(
        val value: Coin,
        val isSent: Boolean,
        val isInternal: Boolean,
        val fee: Coin?,
        val showFee: Boolean
    )
}