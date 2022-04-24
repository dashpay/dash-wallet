package org.dash.wallet.integration.liquid.dialog

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.integration.liquid.adapter.CryptoCurrencyAdapter
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.common.ui.BaseBottomSheetDialog
import org.dash.wallet.integration.liquid.R
import java.util.*


class SellDashCryptoCurrencyDialog(val contexts: Context, val currencyType: String, val currencyArrayList: List<PayloadItem>, val listener: ValueSelectListener) : BaseBottomSheetDialog(contexts) {
    private lateinit var payload: List<PayloadItem>
    private var rvCryptoCurrency: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payload = currencyArrayList
        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_select_cryptocurrency, null)
        setContentView(bottomSheetView)
        bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = contexts.getString(R.string.select_buy_currency)

        rvCryptoCurrency = bottomSheetView.findViewById(R.id.rvCryptoCurrency)
        rvCryptoCurrency?.layoutManager = LinearLayoutManager(contexts)
        rvCryptoCurrency?.adapter = CryptoCurrencyAdapter(contexts, payload, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                // Showing timer for radio button selected currency
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        listener.onItemSelected(value)
                        this@SellDashCryptoCurrencyDialog.dismiss()
                    }
                }, 1000)

            }
        })
    }
}