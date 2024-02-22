/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integrations.coinbase.service

import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.model.TokenResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface CoinBaseAuthApi {
    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun getToken(
        @Field("client_id") clientId: String = CoinBaseClientConstants.CLIENT_ID,
        @Field("redirect_uri") redirectUri: String = CoinbaseConstants.REDIRECT_URL,
        @Field("grant_type") grant_type: String = "authorization_code",
        @Field("client_secret") client_secret: String = CoinBaseClientConstants.CLIENT_SECRET,
        @Field("code") code: String
    ): TokenResponse?

    @POST("oauth/revoke")
    suspend fun revokeToken(@Field("token") token: String)
}
