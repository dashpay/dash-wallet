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

import org.dash.wallet.features.exploredash.data.model.merchent.GetDataMerchantIdRequest
import org.dash.wallet.features.exploredash.data.model.merchent.GetDataMerchantIdResponse
import org.dash.wallet.features.exploredash.data.model.purchase.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.model.purchase.PurchaseGiftCardResponse
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
        @Body getDataMerchantIdRequest: GetDataMerchantIdRequest
    ): GetDataMerchantIdResponse?
}
