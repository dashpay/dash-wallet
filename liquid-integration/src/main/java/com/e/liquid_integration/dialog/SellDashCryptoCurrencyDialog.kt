package com.e.liquid_integration.dialog

import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.e.liquid_integration.R
import com.e.liquid_integration.`interface`.ValueSelectListner
import com.e.liquid_integration.adapter.SellCryptoCurrencyAdapter
import com.e.liquid_integration.currency.PayloadItem
import com.google.android.material.bottomsheet.BottomSheetDialog


class SellDashCryptoCurrencyDialog(val _context: Context, val currencyType: String, val currencyArrayList: List<PayloadItem>, val listner: ValueSelectListner) : BottomSheetDialog(_context) {
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


        if (currencyType.equals("CryptoCurrency")) {
            bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = _context.getString(R.string.select_crypto_currency)
        } else {
            bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = _context.getString(R.string.select_fiat_currency)
        }



        rvCryptoCurrency = bottomSheetView.findViewById(R.id.rvCryptoCurrency)
        rvCryptoCurrency?.layoutManager = LinearLayoutManager(_context)
        rvCryptoCurrency?.adapter = SellCryptoCurrencyAdapter(_context, payload, object : ValueSelectListner {
            override fun onItemSelected(value: Int) {
                listner.onItemSelected(value)
                dialog.dismiss()
            }
        })

    }
}