package org.dash.wallet.integration.liquid.model

import com.google.gson.annotations.SerializedName

data class UIEvent(

	@field:SerializedName("data")
	val data: UIEventData? = null,

	@field:SerializedName("event")
	val event: String? = null
)

data class UIEventData(

	@field:SerializedName("ui_event")
	val uiEvent: String? = null,

	@field:SerializedName("value")
	val value: String? = null,

	@field:SerializedName("target")
	val target: String? = null
)