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
package org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard

import com.google.gson.annotations.SerializedName

data class GetGiftCardResponse(
    @SerializedName("Data") val `data`: Data? = Data(),
    @SerializedName("ErrorMessage") val errorMessage: String? = null,
    @SerializedName("IsDelayed") val isDelayed: Boolean? = false,
    @SerializedName("Successful") val successful: Boolean? = false
) {

    data class Data(
        @SerializedName("AccountCreditUsed") val accountCreditUsed: Double? = 0.0,
        @SerializedName("ActualAmount") val actualAmount: Double? = 0.0,
        @SerializedName("AmountPaid") val amountPaid: Double? = 0.0,
        @SerializedName("AmountSaved") val amountSaved: Double? = 0.0,
        @SerializedName("BalanceInstructions") val balanceInstructions: String? = "",
        @SerializedName("BarcodeUrl") val barcodeUrl: String? = "",
        @SerializedName("CardHistories") val cardHistories: List<CardHistory?>? = listOf(),
        @SerializedName("CardImageUrl") val cardImageUrl: String? = "",
        @SerializedName("CardNumber") val cardNumber: String? = "",
        @SerializedName("CardPin") val cardPin: String? = "",
        @SerializedName("CreatedDate") val createdDate: String? = "",
        @SerializedName("DashPaid") val dashPaid: Double? = 0.0,
        @SerializedName("Id") val id: Int? = 0,
        @SerializedName("IsActive") val isActive: Boolean? = false,
        @SerializedName("IsEmpty") val isEmpty: Boolean? = false,
        @SerializedName("LastTransactionDate") val lastTransactionDate: String? = "",
        @SerializedName("LegalName") val legalName: String? = "",
        @SerializedName("LogoUrl") val logoUrl: String? = "",
        @SerializedName("MerchantId") val merchantId: Int? = 0,
        @SerializedName("MerchantLocationId") val merchantLocationId: Int? = 0,
        @SerializedName("PaymentInstructions") val paymentInstructions: String? = "",
        @SerializedName("PaymentSourceType") val paymentSourceType: Int? = 0,
        @SerializedName("PromoAmount") val promoAmount: Double? = 0.0,
        @SerializedName("PurchaseAmount") val purchaseAmount: Double? = 0.0,
        @SerializedName("RedeemedOn") val redeemedOn: String? = "",
        @SerializedName("RemainingAmount") val remainingAmount: Double? = 0.0,
        @SerializedName("Savings") val savings: Double? = 0.0,
        @SerializedName("SharedOn") val sharedOn: Any? = Any(),
        @SerializedName("SharedTo") val sharedTo: String? = "",
        @SerializedName("SharedToEmail") val sharedToEmail: String? = "",
        @SerializedName("SystemName") val systemName: String? = "",
        @SerializedName("Tip") val tip: Double? = 0.0,
        @SerializedName("TotalDashTransactionAmount") val totalDashTransactionAmount: Any? = Any(),
        @SerializedName("TotalTransactionAmount") val totalTransactionAmount: Double? = 0.0,
        @SerializedName("USDPaid") val uSDPaid: Double? = 0.0,
        @SerializedName("WasShared") val wasShared: Boolean? = false
    ) {
        data class CardHistory(
            @SerializedName("Action") val action: String? = "",
            @SerializedName("CreatedDate") val createdDate: String? = "",
            @SerializedName("UpdatedAmount") val updatedAmount: Double? = 0.0
        )
    }
}
