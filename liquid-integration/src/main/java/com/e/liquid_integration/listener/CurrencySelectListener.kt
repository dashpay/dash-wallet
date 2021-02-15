package com.e.liquid_integration.listener

import com.e.liquid_integration.currency.PayloadItem

interface CurrencySelectListener {
    fun onCurrencySelected(isLiquidSelcted: Boolean, isUpholdSelected: Boolean, selectedFilterCurrencyItem: PayloadItem?)
}