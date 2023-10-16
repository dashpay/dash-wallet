package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserAuthorizationInfoResponse(
    val `data`: Data
): Parcelable

@Parcelize
data class Data(
    val method: String,
    @SerializedName("oauth_meta")
    val oauthMeta: OauthMeta,
    val scopes: List<String>
): Parcelable

@Parcelize
data class OauthMeta(
    @SerializedName("send_limit_amount")
    val sendLimitAmount: String,
    @SerializedName("send_limit_currency")
    val sendLimitCurrency: String,
    @SerializedName("send_limit_period")
    val sendLimitPeriod: String
): Parcelable
