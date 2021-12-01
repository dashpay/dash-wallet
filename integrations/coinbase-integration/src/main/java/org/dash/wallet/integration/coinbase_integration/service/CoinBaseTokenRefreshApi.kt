package org.dash.wallet.integration.coinbase_integration.service

import org.dash.wallet.integration.coinbase_integration.model.TokenResponse
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface CoinBaseTokenRefreshApi {
    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun refreshToken(
        @Field("client_id") clientId: String = "1ca2946d789bf6d986f26df03f4a52a8c6f1fe80e469eb1d3477e7c90768559a",
        @Field("grant_type") grant_type: String = "refresh_token",
        @Field("client_secret") client_secret: String = "4d394e9d2f169a6ad2b16287131a4113c36b35cf38b3d020b75e425055e10c84",
        @Field("refresh_token") refreshToken: String?
    ): Response<TokenResponse>
}
