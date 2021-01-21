package com.e.liquid_integration.model

import com.google.gson.annotations.SerializedName

data class WidgetResponse(

	@field:SerializedName("data")
	val data: Data? = null,

	@field:SerializedName("event")
	val event: String? = null
)