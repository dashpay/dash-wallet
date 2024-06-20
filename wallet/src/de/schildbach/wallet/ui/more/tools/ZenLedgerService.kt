/*
 * Copyright 2024 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more.tools

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

data class AccessTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int,
    val scope: String,
    @SerializedName("created_at") val createdAt: Long
)

data class ZenLedgerCreatePortfolioRequest(
    val portfolio: List<ZenLedgerAddress>
)

data class ZenLedgerAddress(
    val blockchain: String,
    val coin: String,
    val address: String,
    @SerializedName("display_name") val displayName: String
)

data class ZenLedgerCreatePortfolioResponse(
    @SerializedName("api_version") val apiVersion: String,
    val data: Data
)

data class Data(
    @SerializedName("signup_url") val signupUrl: String,
    val aggcode: String
)

interface ZenLedgerService {
    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun getAccessToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("grant_type") grantType: String = "client_credentials"
    ): AccessTokenResponse?

    @POST("aggregators/api/v1/portfolios/")
    suspend fun createPortfolio(
        @Header("Authorization") authorization: String,
        @Body request: ZenLedgerCreatePortfolioRequest,
        @Header("Content-Type") contentType: String = "application/json"
    ): ZenLedgerCreatePortfolioResponse?
}
