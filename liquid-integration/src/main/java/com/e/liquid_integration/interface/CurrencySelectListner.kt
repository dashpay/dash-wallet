package com.e.liquid_integration.`interface`

import com.e.liquid_integration.currency.PayloadItem

interface CurrencySelectListner {
    fun onCurrencySelected(isLiquidSelcted: Boolean, isUpholdSelected: Boolean, selectedFilterCurrencyItem: PayloadItem?)
}