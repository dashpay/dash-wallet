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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.api.load
import coil.decode.SvgDecoder
import coil.request.LoadRequest
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem
import java.util.*

fun ImageView.loadSvgOrOthers(myUrl: String?) {
    myUrl?.let {
        if (it.toLowerCase(Locale.ENGLISH).endsWith("svg")) {
            val imageLoader = ImageLoader.Builder(this.context)
                .componentRegistry {
                    add(SvgDecoder(this@loadSvgOrOthers.context))
                }
                .build()
            val request = LoadRequest.Builder(this.context)
                .data(it)
                .target(this)
                .build()
            imageLoader.execute(request)
        } else {
            this.load(myUrl)
        }
    }
}

data class CurrencyItem(val type: Int, val currency: PayloadItem?)

class CurrencyAdapter(
    val layoutInflater: LayoutInflater,
    private val currencyArrayList: List<PayloadItem>,
    val listener: ValueSelectListener
) : RecyclerView.Adapter<CurrencyAdapter.AbstractViewHolder>() {

    companion object {
        const val TYPE_CURRENCY = 0
        const val TYPE_EMPTY = 1
    }

    private var selectedPosition = -1
    private var filteredList: List<CurrencyItem> = currencyArrayList.map {
        CurrencyItem(TYPE_CURRENCY, it)
    }
    private var lastQuery: String = ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbstractViewHolder {
        return if (viewType == TYPE_CURRENCY) {
            val view = layoutInflater.inflate(R.layout.item_currency, parent, false)
            CurrencyViewHolder(view)
        } else {
            val view = layoutInflater.inflate(R.layout.item_currency_empty, parent, false)
            EmptyViewHolder(view, lastQuery)
        }
    }

    override fun getItemCount(): Int {
        return filteredList.size
    }

    fun setSelectedPositions(position: Int) {
        selectedPosition = position
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

    fun getItemPosition(item: PayloadItem): Int {
        for (i in filteredList.indices) {
            val item = filteredList[i];
            if (item.type == TYPE_CURRENCY) {
                if (item.currency!!.label?.equals(item.currency.label, true) == true) {
                    return i
                }
            }
        }
        return -1
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

    override fun onBindViewHolder(holder: AbstractViewHolder, position: Int) {

        if (filteredList[position].type == TYPE_CURRENCY) {
            holder as CurrencyViewHolder
            val item = filteredList[position].currency!!
            holder.separater.isVisible = position != 0
            if (selectedPosition == position) {
                holder.imgCheckBox.setImageResource(R.drawable.ic_radio_round_checked)
            } else {
                holder.imgCheckBox.setImageResource(R.drawable.ic_radio_round_unchecked)
            }

            holder.txtCurrency.text = if (!item.ccyCode.isNullOrEmpty()) {
                item.ccyCode
            } else {
                item.symbol
            }
            holder.txtCurrencyName.text = item.label

            holder.rlCurrency.setOnClickListener {
                val copyOfLastCheckedPosition: Int = selectedPosition
                selectedPosition = position
                notifyItemChanged(copyOfLastCheckedPosition)
                notifyItemChanged(selectedPosition)

                listener.onItemSelected(position)
            }
            holder.currencyImage.loadSvgOrOthers(item.icon)
        } else {
            holder as EmptyViewHolder
            holder.message.text = holder.itemView.context.getString(R.string.no_results, lastQuery)
        }
    }

    abstract class AbstractViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    }

    class CurrencyViewHolder(itemView: View) : AbstractViewHolder(itemView) {
        val txtCurrency = itemView.findViewById<TextView>(R.id.currency_code)
        val txtCurrencyName = itemView.findViewById<TextView>(R.id.currency_name)
        val rlCurrency = itemView.findViewById<RelativeLayout>(R.id.rlCurrency)
        val imgCheckBox = itemView.findViewById<ImageView>(R.id.radio_button)
        val currencyImage = itemView.findViewById<ImageView>(R.id.currency_image)
        val separater = itemView.findViewById<View>(R.id.item_separater)
    }

    class EmptyViewHolder(itemView: View, val lastQuery: String) : AbstractViewHolder(itemView) {
        val title = itemView.findViewById<TextView>(R.id.title)
        val message = itemView.findViewById<TextView>(R.id.message)
    }
}

