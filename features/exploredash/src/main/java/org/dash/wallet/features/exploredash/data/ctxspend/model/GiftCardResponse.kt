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
package org.dash.wallet.features.exploredash.data.ctxspend.model

import com.google.gson.annotations.SerializedName

data class GiftCardResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String, // unpaid, paid, fulfilled, rejected
    @SerializedName("barcodeUrl") val barcodeUrl: String? = "",
    @SerializedName("number") val cardNumber: String? = "",
    @SerializedName("pin") val cardPin: String? = "",

    @SerializedName("paymentCryptoAmount") val cryptoAmount: String? = "",
    @SerializedName("paymentCryptoCurrency") val cryptoCurrency: String? = "",
    val paymentCryptoNetwork: String = "",
    val paymentId: String = "",
    val percentDiscount: String = "",
    val rate: String = "",
    val redeemUrl: String = "",
    @SerializedName("paymentFiatAmount") val fiatAmount: String? = "",
    @SerializedName("paymentFiatCurrency") val fiatCurrency: String? = "",
    @SerializedName("paymentUrls") val paymentUrls: Map<String, String>? = buildMap { }
)
