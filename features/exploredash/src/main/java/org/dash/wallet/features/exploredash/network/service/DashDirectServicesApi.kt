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
package org.dash.wallet.features.exploredash.network.service

import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.utils.DashDirectConstants
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface DashDirectServicesApi {
    @POST("PurchaseGiftCard")
    suspend fun purchaseGiftCard(
        @Header(DashDirectConstants.requestedUUID) deviceID: String,
        @Header(DashDirectConstants.EMAIL) email: String,
        @Body purchaseGiftCardRequest: PurchaseGiftCardRequest
    ): PurchaseGiftCardResponse?

    @POST("GetMerchantById")
    suspend fun getMerchantById(
        @Header(DashDirectConstants.EMAIL) email: String,
        @Body getMerchantByIdRequest: GetMerchantByIdRequest
    ): GetMerchantByIdResponse?

    @POST("PaymentStatus")
    suspend fun getPaymentStatus(
        @Header(DashDirectConstants.EMAIL) email: String,
        @Body paymentStatusRequest: PaymentStatusRequest
    ): PaymentStatusResponse?

    @POST("GetGiftCard")
    suspend fun getGiftCard(
        @Header(DashDirectConstants.EMAIL) email: String,
        @Body getGiftCardRequest: GetGiftCardRequest
    ): GetGiftCardResponse?
}
