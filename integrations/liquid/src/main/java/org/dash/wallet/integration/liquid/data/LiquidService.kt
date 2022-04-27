/*
 * Copyright 2015-present the original author or authors.
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
package org.dash.wallet.integration.liquid.data

import org.dash.wallet.integration.liquid.currency.CurrencyResponse
import retrofit2.Call
import retrofit2.http.*

interface LiquidService {
    //force_kyc param will take you to kyc process without creating new user
    @FormUrlEncoded
    @POST("api/v1/session")
    fun getSessionId(@Field("public_api_key") publicApiKey: String/*, @Field("force_kyc") forceKyc: Boolean*/): Call<LiquidSessionId>

    @GET("api/v1/session/{session_id}/kyc_state")
    fun getUserKycState(@Path("session_id") sessionId: String, @Header("Authorization") token: String): Call<UserKycState>

    @GET("api/v1/session/{session_id}/accounts")
    fun getUserAccount(@Path("session_id") sessionId: String, @Header("Authorization") token: String): Call<String>

    @GET("api/v1/session/{session_id}/accounts/DASH")
    fun getUserAccountAddress(@Path("session_id") sessionId: String, @Query("require_address") requireAddress: Boolean, @Header("Authorization") token: String): Call<String>

    @GET("api/v1/session/{session_id}/terminate")
    fun terminateSession(@Path("session_id") sessionId: String, @Header("Authorization") token: String): Call<LiquidTerminateSession>

    @GET("api/v1/settlement/currencies")
    fun getAllCurrencies(@Query("public_api_key") publicApiKey: String): Call<CurrencyResponse>
}