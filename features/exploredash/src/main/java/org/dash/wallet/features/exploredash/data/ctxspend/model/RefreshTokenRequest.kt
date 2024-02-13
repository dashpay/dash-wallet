package org.dash.wallet.features.exploredash.data.ctxspend.model

import com.google.gson.annotations.SerializedName

data class RefreshTokenRequest(
    @SerializedName("refreshToken") val refreshToken: String? = null
)
