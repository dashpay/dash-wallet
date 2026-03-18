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
import android.graphics.Paint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.transactions.TransactionGroupHeaderViewHolder
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet.ui.transactions.TxResourceMapper
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionGroupHeaderBinding
import de.schildbach.wallet_test.databinding.TransactionRowBinding
import org.bitcoinj.core.*
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.setRoundedBackground
import org.dash.wallet.common.util.GenericUtils

open class HistoryViewHolder(root: View): RecyclerView.ViewHolder(root)

/**
 * ViewHolder for a single transaction row.
 *
 * Extracted as a top-level class so it can be shared between [TransactionAdapter]
 * (a [PagingDataAdapter] used for the live Room-backed path) and [CacheTransactionAdapter]
 * (a plain [ListAdapter] used during the fast cache-display phase).
 *
 * @param itemCountFn  Returns the current adapter item count (used for last-in-group detection).
 * @param getItemFn    Returns the item at the given position, or null if out of range.
 */
class TransactionViewHolder(
    val binding: TransactionRowBinding,
    private val resources: Resources,
    private val drawBackground: Boolean,
    private val dashFormat: MonetaryFormat,
    private val itemCountFn: () -> Int,
    private val getItemFn: (Int) -> HistoryRowView?,
    private val clickListener: (HistoryRowView, Int, Boolean) -> Unit
) : HistoryViewHolder(binding.root) {
    private val iconSize = resources.getDimensionPixelSize(R.dimen.transaction_icon_size)
    private val resourceMapper = TxResourceMapper()
    private val contentColor = resources.getColor(R.color.content_primary, null)
    private val colorSecondaryStatus = resources.getColor(R.color.orange, null)

    init {
        binding.fiatView.setApplyMarkup(false)
    }

    fun bind(txView: TransactionRowView, position: Int) {
        val nextItem = if (itemCountFn() > position + 1) {
            getItemFn(position + 1)
        } else {
            null
        }
        val isLastInGroup = nextItem !is TransactionRowView

        if (drawBackground) {
            binding.root.background = if (isLastInGroup) {
                ResourcesCompat.getDrawable(resources, R.drawable.selectable_rectangle_white_bottom_radius, null)
            } else {
                ResourcesCompat.getDrawable(resources, R.drawable.selectable_rectangle_white, null)
            }
        } else {
            binding.root.updatePadding(
                top = resources.getDimensionPixelOffset(
                    if (position == 0) {
                        R.dimen.transaction_row_extended_padding
                    } else {
                        R.dimen.transaction_row_vertical_padding
                    }
                )
            )
        }

        binding.root.updatePadding(
            bottom = resources.getDimensionPixelOffset(
                if (isLastInGroup) {
                    R.dimen.transaction_row_extended_padding
                } else {
                    R.dimen.transaction_row_vertical_padding
                }
            )
        )

        setIcon(txView)
        setPrimaryStatus(txView)
        setSecondaryStatus(txView)
        setValue(txView.value, txView.hasErrors)
        setFiatValue(txView.value, txView.exchangeRate)
        setTime(txView.time, resourceMapper.dateTimeFormat)
        setDetails(txView.transactionAmount)
        setComment(txView.comment)
    }

    private fun setIcon(txView: TransactionRowView) {
        val iconBackground = txView.iconBackground
        val icon = txView.icon
        val contact = txView.contact

        if (contact != null) {
            binding.primaryIcon.background = null
            binding.primaryIcon.setPadding(0, 0, 0, 0)
            binding.primaryIcon.load(contact.avatarUrl) {
                transformations(RoundedCornersTransformation(iconSize * 2.toFloat()))
                placeholder(R.drawable.ic_avatar)
                error(R.drawable.ic_avatar)
            }
            binding.primaryIcon.setOnClickListener {
                clickListener.invoke(txView, 0, true)
            }

            binding.secondaryIcon.isVisible = true
            binding.secondaryIcon.setImageResource(icon)
        } else if (txView.iconBitmap != null) {
            binding.primaryIcon.updatePadding(0, 0, 0, 0)
            binding.primaryIcon.background = null
            binding.primaryIcon.load(txView.iconBitmap) {
                transformations(RoundedCornersTransformation(iconSize * 2.toFloat()))
            }
            binding.primaryIcon.setOnClickListener { }
            binding.secondaryIcon.isVisible = true
            binding.secondaryIcon.setImageResource(icon)
        } else {
            val padding = resources.getDimensionPixelOffset(R.dimen.transaction_icon_padding)
            binding.primaryIcon.updatePadding(padding, padding, padding, padding)
            binding.primaryIcon.setRoundedBackground(iconBackground!!)
            binding.primaryIcon.load(icon)
            binding.primaryIcon.setOnClickListener { }
            binding.secondaryIcon.isVisible = false
        }
    }

    private fun setPrimaryStatus(txView: TransactionRowView) {
        if (txView.contact != null) {
            val name = txView.contact.displayName.ifEmpty { txView.contact.username }
            binding.primaryStatus.text = name
        } else if (txView.title != null) {
            binding.primaryStatus.text = txView.title!!.format(resources)
        }

        binding.primaryStatus.setTextColor(contentColor)
    }

    private fun setSecondaryStatus(txView: TransactionRowView) {
        if (txView.statusRes > 0) {
            binding.secondaryStatus.text = resources.getString(txView.statusRes)
            binding.secondaryStatus.setTextColor(colorSecondaryStatus)
        } else if (!txView.statusText.isNullOrEmpty()) {
            binding.secondaryStatus.text = txView.statusText
            binding.secondaryStatus.setTextColor(colorSecondaryStatus)
        } else {
            binding.secondaryStatus.text = null
        }
    }

    private fun setDetails(transactionAmount: Int) {
        if (transactionAmount > 1) {
            binding.details.isVisible = true
            binding.details.text = resources.getString(R.string.transaction_count, transactionAmount)
        } else {
            binding.details.isVisible = false
        }
    }

    private fun setComment(comment: String) {
        binding.comment.text = comment
        binding.comment.isVisible = comment.isNotEmpty()
    }

    private fun setTime(time: Long, dateTimeFormat: Int) {
        binding.time.text = DateUtils.formatDateTime(
            itemView.context,
            time,
            dateTimeFormat
        )
    }

    private fun setValue(value: org.bitcoinj.core.Coin, hasErrors: Boolean) {
        binding.signal.isVisible = !value.isZero

        if (value.isPositive) {
            binding.signal.text = "+"
            binding.value.text = dashFormat.format(value)
        } else if (value.isNegative) {
            binding.signal.text = "−"
            binding.value.text = dashFormat.format(value.negate())
        } else {
            binding.value.text = dashFormat.format(org.bitcoinj.core.Coin.ZERO)
        }

        if (hasErrors) {
            binding.value.paintFlags = binding.value.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            binding.value.paintFlags = binding.value.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun setFiatValue(value: org.bitcoinj.core.Coin, exchangeRate: ExchangeRate?) {
        if (!value.isZero) {
            if (exchangeRate != null) {
                val exchangeCurrencyCode = GenericUtils.currencySymbol(exchangeRate.fiat.currencyCode)
                binding.fiatView.setFiatAmount(
                    value,
                    exchangeRate,
                    Constants.LOCAL_FORMAT,
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

/** Shared [DiffUtil.ItemCallback] for both [TransactionAdapter] and [CacheTransactionAdapter]. */
class HistoryRowDiffCallback : DiffUtil.ItemCallback<HistoryRowView>() {
    override fun areItemsTheSame(oldItem: HistoryRowView, newItem: HistoryRowView): Boolean {
        val sameTransactions = (oldItem is TransactionRowView && newItem is TransactionRowView) &&
            oldItem.id == newItem.id
        return sameTransactions || oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: HistoryRowView, newItem: HistoryRowView): Boolean {
        return oldItem == newItem
    }
}

/**
 * Room/Paging3-backed adapter for the live transaction list.
 * Used after the wallet finishes loading and [MainViewModel] transitions to [TxDataSource.RoomLive].
 */
class TransactionAdapter(
    private val dashFormat: MonetaryFormat,
    private val resources: Resources,
    private val drawBackground: Boolean = false,
    private val clickListener: (HistoryRowView, Int, Boolean) -> Unit
) : PagingDataAdapter<HistoryRowView, HistoryViewHolder>(HistoryRowDiffCallback()) {

    // Keep for backwards compatibility (TransactionGroupDetailsFragment uses it)
    class DiffCallback : DiffUtil.ItemCallback<HistoryRowView>() {
        override fun areItemsTheSame(oldItem: HistoryRowView, newItem: HistoryRowView) =
            HistoryRowDiffCallback().areItemsTheSame(oldItem, newItem)
        override fun areContentsTheSame(oldItem: HistoryRowView, newItem: HistoryRowView) =
            HistoryRowDiffCallback().areContentsTheSame(oldItem, newItem)
    }

    override fun getItemViewType(position: Int): Int {
        if (position >= itemCount) {
            return -1
        }

        return when (getItem(position)) {
            is TransactionRowView -> R.layout.transaction_row
            is HistoryRowView -> R.layout.transaction_group_header
            else -> -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            R.layout.transaction_row -> {
                val binding = TransactionRowBinding.inflate(inflater, parent, false)
                TransactionViewHolder(
                    binding, resources, drawBackground, dashFormat,
                    { itemCount }, { pos -> getItem(pos) },
                    clickListener
                )
            }
            R.layout.transaction_group_header -> {
                val binding = TransactionGroupHeaderBinding.inflate(inflater, parent, false)
                TransactionGroupHeaderViewHolder(binding)
            }
            else -> {
                throw IllegalArgumentException("viewType $viewType isn't recognized")
            }
        }
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = getItem(position)

        when (holder) {
            is TransactionViewHolder -> {
                holder.bind(item as TransactionRowView, position)
                holder.binding.root.setOnClickListener { clickListener.invoke(item, position, false) }
            }
            is TransactionGroupHeaderViewHolder -> {
                val date = (item as HistoryRowView).localDate ?: return
                holder.bind(date)
                holder.binding.root.setOnClickListener { clickListener.invoke(item, position, false) }
            }
        }
    }
}

/**
 * Plain [ListAdapter] for the cache display phase — shown immediately on startup before the wallet
 * finishes loading.  Uses [ListAdapter.submitList] which is a single background DiffUtil + one
 * main-thread handler post, far cheaper than [PagingDataAdapter.submitData]'s coroutine chain.
 *
 * Shares [TransactionViewHolder] with [TransactionAdapter].
 */
class CacheTransactionAdapter(
    private val dashFormat: MonetaryFormat,
    private val resources: Resources,
    private val drawBackground: Boolean = false,
    private val clickListener: (HistoryRowView, Int, Boolean) -> Unit
) : ListAdapter<HistoryRowView, HistoryViewHolder>(HistoryRowDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is TransactionRowView -> R.layout.transaction_row
            is HistoryRowView -> R.layout.transaction_group_header
            else -> -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            R.layout.transaction_row -> {
                val binding = TransactionRowBinding.inflate(inflater, parent, false)
                TransactionViewHolder(
                    binding, resources, drawBackground, dashFormat,
                    { itemCount }, { pos -> currentList.getOrNull(pos) },
                    clickListener
                )
            }
            R.layout.transaction_group_header -> {
                val binding = TransactionGroupHeaderBinding.inflate(inflater, parent, false)
                TransactionGroupHeaderViewHolder(binding)
            }
            else -> {
                throw IllegalArgumentException("viewType $viewType isn't recognized")
            }
        }
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = getItem(position)

        when (holder) {
            is TransactionViewHolder -> {
                holder.bind(item as TransactionRowView, position)
                holder.binding.root.setOnClickListener { clickListener.invoke(item, position, false) }
            }
            is TransactionGroupHeaderViewHolder -> {
                val date = (item as HistoryRowView).localDate ?: return
                holder.bind(date)
                holder.binding.root.setOnClickListener { clickListener.invoke(item, position, false) }
            }
        }
    }
}