package org.dash.wallet.integration.liquid.listener

import org.dash.wallet.integration.liquid.currency.PayloadItem

interface CurrencySelectListener {
    fun onCurrencySelected(isLiquidSelcted: Boolean, isUpholdSelected: Boolean, selectedFilterCurrencyItem: PayloadItem?)
}