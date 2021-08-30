package org.dash.wallet.integration.liquid.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.currency.PayloadItem


class CryptoCurrencyAdapter(val context: Context, val
currencyArrayList: List<PayloadItem>, val listener: ValueSelectListener) : RecyclerView.Adapter<CryptoCurrencyAdapter.CurrencyViewHolder>() {

    var layoutInflater: LayoutInflater? = null
    var selectedPosition = -1

    init {
        layoutInflater =
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
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


        holder.txtCurrency.text = currencyArrayList[position].ccyCode
        holder.txtCurrencyName.text = currencyArrayList[position].label

        holder.rlCurrency.setOnClickListener {
            val copyOfLastCheckedPosition: Int = selectedPosition
            selectedPosition = position
            notifyItemChanged(copyOfLastCheckedPosition)
            notifyItemChanged(selectedPosition)

            listener.onItemSelected(position)

        }
    }


    class CurrencyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val txtCurrency = itemView.findViewById<TextView>(R.id.txtCurrency)
        val txtCurrencyName = itemView.findViewById<TextView>(R.id.txtCurrencyName)
        val rlCurrency = itemView.findViewById<RelativeLayout>(R.id.rlCurrency)
        val imgCheckBox = itemView.findViewById<ImageView>(R.id.imgCheckBox)
    }
}

