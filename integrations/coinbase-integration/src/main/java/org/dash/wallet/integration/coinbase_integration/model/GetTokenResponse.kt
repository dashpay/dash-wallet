package org.dash.wallet.integration.coinbase_integration.model

data class GetTokenResponse(
    val access_token: String,
    val created_at: Int,
    val expires_in: Int,
    val refresh_token: String,
    val scope: String,
    val token_type: String
)
