package org.dash.wallet.integration.liquid.currency

import com.google.gson.annotations.SerializedName

data class CurrencyResponse(

        @field:SerializedName("environment")
        val environment: String? = null,

        @field:SerializedName("payload")
        val payload: List<PayloadItem> = ArrayList(),

        @field:SerializedName("success")
        val success: Boolean? = null,

        @field:SerializedName("message")
        val message: String? = null
)