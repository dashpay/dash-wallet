package org.dash.wallet.integration.liquid.dialog

import android.app.Activity
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.adapter.CurrencyAdapter
import java.util.*


class SellDashCryptoCurrencyDialog(
    activity: Activity,
    val currencyType: String,
    val payload: List<PayloadItem>,
    val listener: (Int) -> Unit)
    : CurrencySearchDialog(activity, null, R.string.select_buy_currency) {

    override fun generateList() {
        currencyArrayList.addAll(payload)
    }

    override fun createAdapter(): CurrencyAdapter {
        return CurrencyAdapter(activity, payload) {
                // Showing timer for radio button selected currency
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        listener(it)
                        dialog.dismiss()
                    }
                }, 1000)
            }
    }
}