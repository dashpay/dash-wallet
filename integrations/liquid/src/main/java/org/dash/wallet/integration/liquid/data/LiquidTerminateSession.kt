package org.dash.wallet.integration.liquid.data

import com.google.gson.annotations.SerializedName

data class LiquidTerminateSession(

	@field:SerializedName("environment")
	val environment: String? = null,

	@field:SerializedName("payload")
	val payload: Boolean? = null,

	@field:SerializedName("success")
	val success: Boolean? = null,

	@field:SerializedName("message")
	val message: String? = null
)
