package org.dash.wallet.common.data

import org.bitcoinj.utils.Fiat

@Deprecated("Use ExchangeRate instead")
data class ExchangeRateData(
        val currencyCode: String,
        val rate: String,
        val currencyName: String,
        val fiat: Fiat
)