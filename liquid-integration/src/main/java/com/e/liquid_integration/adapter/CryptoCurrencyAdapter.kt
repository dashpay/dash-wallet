package com.e.liquid_integration.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.e.liquid_integration.R
import com.e.liquid_integration.`interface`.ValueSelectListner
import com.e.liquid_integration.currency.PayloadItem


class CryptoCurrencyAdapter(val _context: Context, val
currencyArrayList: List<PayloadItem>, val listner: ValueSelectListner) : RecyclerView.Adapter<CryptoCurrencyAdapter.CurrencyViewHolder>() {

    var layoutInflater: LayoutInflater? = null
    var selectedPosition = -1

    init {
        layoutInflater =
                _context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CurrencyViewHolder {
        val view = layoutInflater!!.inflate(R.layout.item_buy_cryptocurrency, parent, false)
        return CurrencyViewHolder(view)
    }

    override fun getItemCount(): Int {
        return currencyArrayList.size
    }

    override fun onBindViewHolder(holder: CurrencyViewHolder, position: Int) {

        if (selectedPosition == position) {
            holder.imgCheckBox.setImageResource(R.drawable.ic_radio_round_checked)
        } else {
            holder.imgCheckBox.setImageResource(R.drawable.ic_radio_round_unchecked)
        }


        holder.txtCurrency.text = currencyArrayList[position].symbol
        holder.txtCurrencyName.text = currencyArrayList[position].label

        holder.rlCurrency.setOnClickListener {
            val copyOfLastCheckedPosition: Int = selectedPosition
            selectedPosition = position
            notifyItemChanged(copyOfLastCheckedPosition)
            notifyItemChanged(selectedPosition)

            listner.onItemSelected(position)

        }
    }


    class CurrencyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val txtCurrency = itemView.findViewById<TextView>(R.id.txtCurrency)
        val txtCurrencyName = itemView.findViewById<TextView>(R.id.txtCurrencyName)
        val rlCurrency = itemView.findViewById<RelativeLayout>(R.id.rlCurrency)
        val imgCheckBox = itemView.findViewById<ImageView>(R.id.imgCheckBox)
    }
}

