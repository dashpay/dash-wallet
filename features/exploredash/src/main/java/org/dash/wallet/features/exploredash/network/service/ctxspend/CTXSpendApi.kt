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
package org.dash.wallet.features.exploredash.network.service.ctxspend

import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.RefreshTokenResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.VerifyEmailRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CTXSpendApi {
    @POST("login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<Unit>

    @POST("verify-email")
    suspend fun verifyEmail(@Body verifyEmailRequest: VerifyEmailRequest): RefreshTokenResponse?

    @POST("gift-cards")
    suspend fun purchaseGiftCard(@Body purchaseGiftCardRequest: PurchaseGiftCardRequest): GiftCardResponse?

    @GET("gift-cards")
    suspend fun getGiftCard(@Query("txid") txid: String): GiftCardResponse?

    @GET("merchants/{id}")
    suspend fun getMerchant(@Path("id") id: String): GetMerchantResponse?
}
