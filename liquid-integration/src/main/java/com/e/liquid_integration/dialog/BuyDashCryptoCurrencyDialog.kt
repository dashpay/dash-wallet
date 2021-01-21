package com.e.liquid_integration.dialog

import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.e.liquid_integration.R
import com.e.liquid_integration.`interface`.ValueSelectListner
import com.e.liquid_integration.adapter.CryptoCurrencyAdapter
import com.e.liquid_integration.currency.PayloadItem
import com.google.android.material.bottomsheet.BottomSheetDialog


class BuyDashCryptoCurrencyDialog(val _context: Context, val currencyArrayList: List<PayloadItem>, val listner: ValueSelectListner) : BottomSheetDialog(_context) {
    private val payload: List<PayloadItem>
    private var rvCryptoCurrency: RecyclerView? = null

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        payload = currencyArrayList
        create()
    }

    override fun create() {

        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_select_cryptocurrency, null)
        val dialog = BottomSheetDialog(_context, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(bottomSheetView);
        dialog.show()
        bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = _context.getString(R.string.select_sell_currency)

        rvCryptoCurrency = bottomSheetView.findViewById(R.id.rvCryptoCurrency)
        rvCryptoCurrency?.layoutManager = LinearLayoutManager(_context)
        rvCryptoCurrency?.adapter = CryptoCurrencyAdapter(_context, payload, object : ValueSelectListner {
            override fun onItemSelected(value: Int) {
                listner.onItemSelected(value)
                dialog.dismiss()
            }
        })

    }
}