/*
 * Copyright 2021 the original author or authors.
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

package org.dash.wallet.integration.liquid.adapter

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.github.twocoffeesoneteam.glidetovectoryou.GlideToVectorYou
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.databinding.ItemCurrencyBinding
import org.dash.wallet.integration.liquid.databinding.ItemCurrencyEmptyBinding


data class CurrencyItem(val type: Int, val currency: PayloadItem?)
class CurrencyItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

class CurrencyAdapter(
    val activity: Activity,
    private val currencyArrayList: List<PayloadItem>,
    val listener: ValueSelectListener
) : RecyclerView.Adapter<CurrencyItemViewHolder>() {

    companion object {
        const val TYPE_CURRENCY = 0
        const val TYPE_EMPTY = 1
    }

    private var selectedPosition = -1
    private var selectedItem: PayloadItem? = null
    private var filteredList: List<CurrencyItem> = currencyArrayList.map {
        CurrencyItem(TYPE_CURRENCY, it)
    }
    private var lastQuery: String = ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CurrencyItemViewHolder {
        return if (viewType == TYPE_CURRENCY) {
            val view = ItemCurrencyBinding.inflate(LayoutInflater.from(activity), parent, false).root
            CurrencyItemViewHolder(view)
        } else {
            val view = ItemCurrencyEmptyBinding.inflate(LayoutInflater.from(activity), parent, false).root
            CurrencyItemViewHolder(view)
        }
    }

    override fun getItemCount(): Int {
        return filteredList.size
    }
    fun setSelectedPosition(position: Int, item: PayloadItem?) {
        selectedPosition = position
        selectedItem = item
        notifyItemChanged(position)
    }

    override fun getItemId(position: Int): Long {
        return if (filteredList[position].type == TYPE_CURRENCY) {
            filteredList[position].currency?.label?.hashCode()?.toLong() ?: 0L
        } else {
            1L
        }
    }

    override fun getItemViewType(position: Int): Int {
        return filteredList[position].type
    }

    @SuppressLint("NotifyDataSetChanged")
    fun filter(text: String) {
        lastQuery = text
        filteredList = currencyArrayList.filter {
            (it.symbol?.contains(text, ignoreCase = true) ?: false) ||
                    (it.ccyCode?.contains(text, ignoreCase = true) ?: false) ||
                    (it.label?.contains(text, ignoreCase = true) ?: false)
        }.map {
            CurrencyItem(TYPE_CURRENCY, it)
        }

        if (filteredList.isEmpty()) {
            filteredList = arrayListOf(CurrencyItem(TYPE_EMPTY, null))
        }
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: CurrencyItemViewHolder, position: Int) {

        val item = filteredList[position]
        if (item.type == TYPE_CURRENCY) {
            ItemCurrencyBinding.bind(holder.itemView).apply {
                val currencyItem = filteredList[position].currency!!
                itemSeparator.isVisible = position != 0
                if (selectedItem?.symbol == currencyItem.symbol) {
                    radioButton.setImageResource(R.drawable.ic_radio_round_checked)
                } else {
                    radioButton.setImageResource(R.drawable.ic_radio_round_unchecked)
                }

                currencyCode.text = if (!currencyItem.ccyCode.isNullOrEmpty()) {
                    currencyItem.ccyCode
                } else {
                    currencyItem.symbol
                }
                currencyName.text = currencyItem.label

                rlCurrency.setOnClickListener {
                    val copyOfLastCheckedPosition: Int = filteredList.indexOf(
                        CurrencyItem(TYPE_CURRENCY,selectedItem)
                    )
                    selectedPosition = position
                    notifyItemChanged(copyOfLastCheckedPosition)
                    notifyItemChanged(selectedPosition)

                    listener.onItemSelected(position)
                }
                // Does this load only SVG's?
                GlideToVectorYou.justLoadImage(activity, Uri.parse(currencyItem.icon), currencyImage)
            }
        } else {
            ItemCurrencyEmptyBinding.bind(holder.itemView).apply {
                message.text = activity.getString(R.string.no_results, lastQuery)
            }
        }
    }
}

