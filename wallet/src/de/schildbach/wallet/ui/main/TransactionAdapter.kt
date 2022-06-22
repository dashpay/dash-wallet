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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet.ui.transactions.TxResourceMapper
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionRowBinding
import org.bitcoinj.core.*
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.getRoundedBackground
import org.dash.wallet.common.ui.getRoundedRippleBackground
import org.dash.wallet.common.util.GenericUtils

class TransactionAdapter(
    private val dashFormat: MonetaryFormat,
    private val resources: Resources,
    private val drawBackground: Boolean = false,
    private val clickListener: (TransactionRowView, Int) -> Unit
) : ListAdapter<TransactionRowView, TransactionAdapter.TransactionViewHolder>(DiffCallback()) {
    private val contentColor = resources.getColor(R.color.content_primary, null)
    private val warningColor = resources.getColor(R.color.content_warning, null)
    private val colorSecondaryStatus = resources.getColor(R.color.secondary_status, null)

    class DiffCallback : DiffUtil.ItemCallback<TransactionRowView>() {
        override fun areItemsTheSame(oldItem: TransactionRowView, newItem: TransactionRowView): Boolean {
            return oldItem.txId == newItem.txId
        }

        override fun areContentsTheSame(oldItem: TransactionRowView, newItem: TransactionRowView): Boolean {
            return oldItem == newItem
        }
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val tx = getItem(position)
        holder.bind(tx)

        holder.itemView.setOnClickListener {
            clickListener.invoke(tx, position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = TransactionRowBinding.inflate(inflater, parent, false)

        return TransactionViewHolder(binding)
    }

    inner class TransactionViewHolder(
        private val binding: TransactionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val resourceMapper = TxResourceMapper()

        init {
            binding.fiatView.setApplyMarkup(false)
        }

        fun bind(txView: TransactionRowView) {
            if (drawBackground) {
                binding.root.background = resources.getRoundedRippleBackground(R.style.TransactionRowBackground)
            }

            binding.icon.setImageResource(txView.icon)
            binding.icon.background = resources.getRoundedBackground(txView.iconBackground)

            binding.primaryStatus.text = resources.getString(txView.title)
            binding.primaryStatus.setTextColor(if (txView.hasErrors) {
                warningColor
            } else {
                contentColor
            })

            if (txView.status < 0) {
                binding.secondaryStatus.text = null
            } else {
                binding.secondaryStatus.text = resources.getString(txView.status)
                binding.secondaryStatus.setTextColor(if (txView.hasErrors) {
                    warningColor
                } else {
                    colorSecondaryStatus
                })
            }

            setValue(txView.value, txView.hasErrors)
            setFiatValue(txView.value, txView.exchangeRate)
            setTime(txView.time, resourceMapper.dateTimeFormat)
            setDetails(txView.transactionAmount)
        }

        private fun setDetails(transactionAmount: Int) {
            if (transactionAmount > 1) {
                binding.details.isVisible = true
                binding.details.text = resources.getString(R.string.transaction_count, transactionAmount)
            } else {
                binding.details.isVisible = false
            }
        }

        private fun setTime(time: Long, dateTimeFormat: Int) {
            // Set the time. eg.  "<date> <time>"
            binding.time.text = DateUtils.formatDateTime(
                itemView.context, time,
                dateTimeFormat
            )
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
                        value, exchangeRate, Constants.LOCAL_FORMAT,
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
}