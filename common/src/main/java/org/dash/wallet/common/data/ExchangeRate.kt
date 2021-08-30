package org.dash.wallet.common.data

import org.bitcoinj.utils.Fiat

data class ExchangeRate(
        val currencyCode: String,
        val rate: String,
        val currencyName: String,
        val fiat: Fiat
)