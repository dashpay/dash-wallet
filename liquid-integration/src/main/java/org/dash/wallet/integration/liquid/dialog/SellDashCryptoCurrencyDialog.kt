package org.dash.wallet.integration.liquid.dialog

import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.integration.liquid.adapter.CryptoCurrencyAdapter
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.dash.wallet.integration.liquid.R
import java.util.*


class SellDashCryptoCurrencyDialog(val contexts: Context, val currencyType: String, val currencyArrayList: List<PayloadItem>, val listener: ValueSelectListener) : BottomSheetDialog(contexts) {
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
        val dialog = BottomSheetDialog(contexts, R.style.BottomSheetDialog)
        dialog.setContentView(bottomSheetView);
        dialog.show()


        /*if (currencyType.equals("CryptoCurrency")) {
            bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = contexts.getString(R.string.select_crypto_currency)
        } else {
            bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = contexts.getString(R.string.select_fiat_currency)
        }*/

        bottomSheetView.findViewById<TextView>(R.id.txtCurrencyType).text = contexts.getString(R.string.select_buy_currency)


        rvCryptoCurrency = bottomSheetView.findViewById(R.id.rvCryptoCurrency)
        rvCryptoCurrency?.layoutManager = LinearLayoutManager(contexts)
        rvCryptoCurrency?.adapter = CryptoCurrencyAdapter(contexts, payload, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                // Showing timer for radio button selected currency
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        listener.onItemSelected(value)
                        dialog.dismiss()
                    }
                }, 1000)

            }
        })

    }
}