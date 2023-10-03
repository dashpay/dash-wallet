/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integrations.uphold.api

import org.dash.wallet.integrations.uphold.data.UpholdAccessToken
import org.dash.wallet.integrations.uphold.data.UpholdCapability
import org.dash.wallet.integrations.uphold.data.UpholdCard
import org.dash.wallet.integrations.uphold.data.UpholdCryptoCardAddress
import org.dash.wallet.integrations.uphold.data.UpholdTransaction
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

interface UpholdService {
    @FormUrlEncoded
    @POST("oauth2/token")
    suspend fun getAccessToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String
    ): Response<UpholdAccessToken?>

    @FormUrlEncoded
    @POST("oauth2/revoke")
    suspend fun revokeAccessToken(@Field("token") token: String): Response<String?>

    @get:GET("v0/me/cards")
    val cards: Call<List<UpholdCard>>

    @POST("v0/me/cards")
    fun createCard(@Body body: Map<String, String>): Call<UpholdCard?>

    @POST("v0/me/cards/{id}/addresses")
    fun createCardAddress(@Path("id") cardId: String, @Body body: Map<String, String>): Call<UpholdCryptoCardAddress?>

    @POST("v0/me/cards/{cardId}/transactions")
    @JvmSuppressWildcards
    fun createTransaction(@Path("cardId") cardId: String, @Body body: Map<String, Any>): Call<UpholdTransaction?>

    @POST("v0/me/cards/{cardId}/transactions/{txId}/commit")
    fun commitTransaction(@Path("cardId") cardId: String, @Path("txId") txId: String?): Call<Any?>

    @GET
    fun getUpholdCurrency(@Header("Range") range: String?, @Url url: String): Call<String?>

    @GET("v0/me/capabilities/{operation}")
    suspend fun getCapabilities(@Path("operation") operation: String): Response<UpholdCapability?>
}
