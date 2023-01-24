package org.dash.wallet.features.exploredash.data.model.merchent

import com.google.gson.annotations.SerializedName

data class GetDataMerchantIdRequest(
    @SerializedName("Id")
    val id: Long? = 0,
    @SerializedName("IncludeLocations")
    val includeLocations: Boolean? = false
)
