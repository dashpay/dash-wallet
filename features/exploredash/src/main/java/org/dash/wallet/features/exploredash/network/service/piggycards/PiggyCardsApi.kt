/*
 * Copyright 2025 Dash Core Group.
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
package org.dash.wallet.features.exploredash.network.service.piggycards

import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Location
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginResponse
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Merchant
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.MerchantDetails
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.OrderRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.OrderResponse
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.OrderStatusResponse
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.SignupRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.SignupResponse
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.VerifyOtpRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.VerifyOtpResponse
import retrofit2.http.*

interface PiggyCardsApi {
    @POST("signup")
    suspend fun signup(@Body signupRequest: SignupRequest): SignupResponse

    @POST("verify-otp")
    suspend fun verifyOtp(@Body verifyOtpRequest: VerifyOtpRequest): VerifyOtpResponse

    @POST("login")
    suspend fun login(@Body loginRequest: LoginRequest): LoginResponse

    @GET("merchants")
    suspend fun getMerchants(@Query("country") country: String): List<Merchant>

    @GET("merchants/{id}")
    suspend fun getMerchant(@Path("id") id: String): MerchantDetails

    @GET("merchants/{id}/locations")
    suspend fun getMerchantLocations(@Path("id") id: String): List<Location>

    @POST("orders")
    suspend fun createOrder(@Body orderRequest: OrderRequest): OrderResponse

    @GET("orders/{id}")
    suspend fun getOrderStatus(@Path("id") orderId: String): OrderStatusResponse
}