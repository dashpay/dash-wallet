package org.dash.wallet.integration.uphold.currencyModel

import com.google.gson.annotations.SerializedName

data class UpholdCurrencyResponse(

	@field:SerializedName("symbol")
	val symbol: String? = null,

	@field:SerializedName("code")
	val code: String? = null,

	@field:SerializedName("name")
	val name: String? = null,

	@field:SerializedName("type")
	val type: String? = null,


	@field:SerializedName("status")
	val status: String? = null
)