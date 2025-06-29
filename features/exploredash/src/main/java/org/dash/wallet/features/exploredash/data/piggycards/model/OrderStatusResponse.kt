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
package org.dash.wallet.features.exploredash.data.piggycards.model

import com.google.gson.annotations.SerializedName

data class OrderStatusResponse(
    @SerializedName("code")
    val code: Int,
    @SerializedName("message")
    val message: String,
    @SerializedName("data")
    val data: OrderData
)

data class OrderData(
    @SerializedName("orderId")
    val orderId: String,
    @SerializedName("payTo")
    val payTo: String,
    @SerializedName("delivery_time")
    val deliveryTime: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("cards")
    val cards: List<GiftCard>
)

data class GiftCard(
    @SerializedName("name")
    val name: String,
    @SerializedName("claimCode")
    val claimCode: String?,
    @SerializedName("claimPin")
    val claimPin: String?,
    @SerializedName("barcodelink")
    val barcodeLink: String?,
    @SerializedName("cardStatus")
    val cardStatus: String
)