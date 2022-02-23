package org.dash.wallet.integration.coinbase_integration.model

data class UserAuthorizationInfoResponse(
    val `data`: Data
)

data class Data(
    val method: String,
    val oauth_meta: OauthMeta,
    val scopes: List<String>
)

data class OauthMeta(
    val send_limit_amount: String,
    val send_limit_currency: String,
    val send_limit_period: String
)