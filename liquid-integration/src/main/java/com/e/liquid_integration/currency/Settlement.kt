package com.e.liquid_integration.currency

import com.google.gson.annotations.SerializedName

data class Settlement(

        @field:SerializedName("funding")
        val funding: List<String> = ArrayList(),

        @field:SerializedName("payout")
        val payout: List<String?> = ArrayList()
)