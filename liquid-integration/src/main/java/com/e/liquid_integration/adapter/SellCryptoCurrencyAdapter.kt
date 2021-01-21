package com.e.liquid_integration.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.e.liquid_integration.R
import com.e.liquid_integration.`interface`.ValueSelectListner
import com.e.liquid_integration.currency.PayloadItem


class SellCryptoCurrencyAdapter(val _context: Context, val
currencyArrayList: List<PayloadItem>, val listner: ValueSelectListner) : RecyclerView.Adapter<SellCryptoCurrencyAdapter.CurrencyViewHolder>() {

    var layoutInflater: LayoutInflater? = null
    var selectedPosition = -1

    init {
        layoutInflater =
                _context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CurrencyViewHolder {
        val view = layoutInflater!!.inflate(R.layout.item_cryptocurrency, parent, false)
        return CurrencyViewHolder(view)
    }

    override fun getItemCount(): Int {
        return currencyArrayList.size
    }

    override fun onBindViewHolder(holder: CurrencyViewHolder, position: Int) {

        if (selectedPosition == position) {
            holder.itemView.setBackgroundResource(R.drawable.drawable_currency_border)
        } else {
            holder.itemView.setBackgroundResource(0)
        }


        holder.txtAmount.text = currencyArrayList[position].symbol
        holder.txtCurrencySName.text = currencyArrayList[position].label
        holder.txtCurrencyFName.text = currencyArrayList[position].label

        holder.itemView.setOnClickListener {
            val copyOfLastCheckedPosition: Int = selectedPosition
            selectedPosition = position
            notifyItemChanged(copyOfLastCheckedPosition)
            notifyItemChanged(selectedPosition)

            listner.onItemSelected(position)

        }
    }


    class CurrencyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtAmount = itemView.findViewById<TextView>(R.id.txtAmount)
        val txtCurrencySName = itemView.findViewById<TextView>(R.id.txtCurrencySName)
        val txtCurrencyFName = itemView.findViewById<TextView>(R.id.txtCurrencyFName)
        val imgCurrency = itemView.findViewById<ImageView>(R.id.imgCurrency)

    }
}

