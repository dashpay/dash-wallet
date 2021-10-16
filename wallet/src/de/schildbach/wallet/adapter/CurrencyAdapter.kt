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

package de.schildbach.wallet.adapter

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.api.load
import coil.decode.SvgDecoder
import coil.request.LoadRequest
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem
import de.schildbach.wallet_test.R
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

class CurrencyAdapter(
    val layoutInflater: LayoutInflater,
    private val currencyArrayList: List<PayloadItem>,
    val listener: ValueSelectListener
) : RecyclerView.Adapter<CurrencyAdapter.CurrencyViewHolder>() {

    private var selectedPosition = -1
    private var filteredList: List<PayloadItem> = currencyArrayList

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CurrencyViewHolder {
        val view = layoutInflater.inflate(R.layout.item_currency, parent, false)
        return CurrencyViewHolder(view)
    }

    override fun getItemCount(): Int {
        return filteredList.size
    }

    fun setSelectedPositions(position: Int) {
        selectedPosition = position
        notifyItemChanged(position)
    }

    override fun getItemId(position: Int): Long {
        return filteredList[position].ccyCode?.hashCode()?.toLong() ?: 0L
    }

    @SuppressLint("NotifyDataSetChanged")
    fun filter(text: String) {
        filteredList = currencyArrayList.filter {
            (it.symbol?.contains(text, ignoreCase = true) ?: false) ||
                    (it.ccyCode?.contains(text, ignoreCase = true) ?: false) ||
                    (it.label?.contains(text, ignoreCase = true) ?: false)
        }
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: CurrencyViewHolder, position: Int) {

        if (selectedPosition == position) {
            holder.imgCheckBox.setImageResource(R.drawable.ic_radio_round_checked)
        } else {
            holder.imgCheckBox.setImageResource(R.drawable.ic_radio_round_unchecked)
        }
        holder.txtCurrency.text = filteredList[position].symbol
        holder.txtCurrencyName.text = filteredList[position].label

        holder.rlCurrency.setOnClickListener {
            val copyOfLastCheckedPosition: Int = selectedPosition
            selectedPosition = position
            notifyItemChanged(copyOfLastCheckedPosition)
            notifyItemChanged(selectedPosition)

            listener.onItemSelected(position)
        }
        holder.currencyImage.loadSvgOrOthers(filteredList[position].icon)
    }


    class CurrencyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtCurrency = itemView.findViewById<TextView>(R.id.currency_code)
        val txtCurrencyName = itemView.findViewById<TextView>(R.id.currency_name)
        val rlCurrency = itemView.findViewById<RelativeLayout>(R.id.rlCurrency)
        val imgCheckBox = itemView.findViewById<ImageView>(R.id.radio_button)
        val currencyImage = itemView.findViewById<ImageView>(R.id.currency_image)
    }
}

