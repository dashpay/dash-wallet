package org.dash.wallet.integration.coinbase_integration.model

import androidx.collection.ArrayMap
import com.google.gson.annotations.SerializedName

data class CoinBaseExchangeRate(
    @SerializedName("data")
    val `data`: CoinBaseExchangeRateData?
)

data class CoinBaseExchangeRateData(
    @SerializedName("currency")
    val currency: String?,
    @SerializedName("rates")
    val rates: ArrayMap<String, String>
)
