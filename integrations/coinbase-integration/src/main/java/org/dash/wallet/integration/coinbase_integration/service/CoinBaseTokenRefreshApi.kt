/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
