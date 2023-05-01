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
package org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus

import com.google.gson.annotations.SerializedName

data class PaymentStatusResponse(
    @SerializedName("Data") val `data`: Data? = Data(),
    @SerializedName("ErrorMessage") val errorMessage: String? = "",
    @SerializedName("IsDelayed") val isDelayed: Boolean? = false,
    @SerializedName("Successful") val successful: Boolean? = false
) {
    data class Data(
        @SerializedName("Errors") val errors: List<String?>? = listOf(),
        @SerializedName("gift_card_id") val giftCardId: Long? = 0,
        @SerializedName("order_id") val orderId: String? = "",
        @SerializedName("payment_id") val paymentId: String? = "",
        val status: String? = ""
    )
}
