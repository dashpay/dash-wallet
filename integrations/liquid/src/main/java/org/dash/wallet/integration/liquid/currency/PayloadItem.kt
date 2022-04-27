package org.dash.wallet.integration.liquid.currency

import com.google.gson.annotations.SerializedName

data class PayloadItem(

        @field:SerializedName("symbol")
        val symbol: String? = null,


        @field:SerializedName("icon")
        val icon: String? = null,

        @field:SerializedName("label")
        val label: String? = null,

        @field:SerializedName("type")
        val type: String? = null,

        @field:SerializedName("ccy_code")
        val ccyCode: String? = null,

        @field:SerializedName("settlement")
        val settlement: Settlement? = null
)