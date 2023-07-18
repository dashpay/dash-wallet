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
package org.dash.wallet.features.exploredash.data.dashdirect.model.merchant

import com.google.gson.annotations.SerializedName

data class GetMerchantByIdResponse(
    @SerializedName("AccessToken") val accessToken: Any? = null,
    @SerializedName("Data") val `data`: Data? = Data(),
    @SerializedName("DelayedToken") val delayedToken: Any? = null,
    @SerializedName("ErrorMessage") val errorMessage: String? = null,
    @SerializedName("IsDelayed") val isDelayed: Boolean? = null,
    @SerializedName("Successful") val successful: Boolean? = null
) {
    data class Data(@SerializedName("Merchant") val merchant: Merchant? = Merchant())
    data class Merchant(
        @SerializedName("Id") val id: Int? = 0,
        @SerializedName("MinimumCardPurchase") val minimumCardPurchase: Double? = 0.0,
        @SerializedName("MaximumCardPurchase") val maximumCardPurchase: Double? = 0.0,
        @SerializedName("SavingsPercentage") val savingsPercentage: Double? = 0.0,
        @SerializedName("HasBarcode") val hasBarcode: Boolean? = false
    )
}
