package org.dash.wallet.integration.liquid.model

import com.google.gson.annotations.SerializedName

data class Data(

	@field:SerializedName("formPercent")
	val formPercent: Int? = null,

	@field:SerializedName("new_step")
	val newStep: String? = null,

	@field:SerializedName("old_step")
	val oldStep: String? = null
)