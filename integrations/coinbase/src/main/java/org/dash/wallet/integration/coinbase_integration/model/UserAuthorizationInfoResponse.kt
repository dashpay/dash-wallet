package org.dash.wallet.integration.coinbase_integration.model

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
    val oauth_meta: OauthMeta,
    val scopes: List<String>
): Parcelable

@Parcelize
data class OauthMeta(
    @SerializedName("send_limit_amount")
    val send_limit_amount: String,
    @SerializedName("send_limit_currency")
    val send_limit_currency: String,
    @SerializedName("send_limit_period")
    val send_limit_period: String
): Parcelable