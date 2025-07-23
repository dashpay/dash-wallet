package org.dash.wallet.features.exploredash.data.dashspend.ctx.model

import com.google.gson.annotations.SerializedName

data class RefreshTokenRequest(
    @SerializedName("refreshToken") val refreshToken: String? = null
)
