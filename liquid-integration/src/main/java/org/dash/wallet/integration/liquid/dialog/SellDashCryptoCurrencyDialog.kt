package org.dash.wallet.integration.liquid.dialog

import android.app.Activity
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.integration.liquid.adapter.CurrencyAdapter
import java.util.*


class SellDashCryptoCurrencyDialog(
    activity: Activity,
    val currencyType: String,
    val payload: List<PayloadItem>,
    val listener: ValueSelectListener)
    : CurrencyDialog(activity, null, R.string.select_buy_currency) {

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        create()
    }

    override fun generateList() {
        currencyArrayList.addAll(payload)
    }

    override fun createAdapter(): CurrencyAdapter {
        return CurrencyAdapter(activity, payload, object : ValueSelectListener {
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