package org.dash.wallet.features.exploredash.data.dashspend.ctx.model

import com.google.gson.annotations.SerializedName

data class RefreshTokenResponse(
    @SerializedName("refreshToken") val refreshToken: String? = null,
    @SerializedName("accessToken") val accessToken: String? = null
)
