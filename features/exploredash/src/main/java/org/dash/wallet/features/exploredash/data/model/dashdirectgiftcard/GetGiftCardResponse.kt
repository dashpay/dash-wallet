package org.dash.wallet.features.exploredash.data.model.dashdirectgiftcard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class GetGiftCardResponse(
    @SerialName("Data")
    val `data`: Data? = Data(),
    @SerialName("ErrorMessage")
    val errorMessage: String? = "",
    @SerialName("IsDelayed")
    val isDelayed: Boolean? = false,
    @SerialName("Successful")
    val successful: Boolean? = false
) {

    data class Data(
        @SerialName("AccountCreditUsed")
        val accountCreditUsed: Double? = 0.0,
        @SerialName("ActualAmount")
        val actualAmount: Double? = 0.0,
        @SerialName("AmountPaid")
        val amountPaid: Double? = 0.0,
        @SerialName("AmountSaved")
        val amountSaved: Double? = 0.0,
        @SerialName("BalanceInstructions")
        val balanceInstructions: String? = "",
        @SerialName("BarcodeUrl")
        val barcodeUrl: String? = "",
        @SerialName("CardHistories")
        val cardHistories: List<CardHistory?>? = listOf(),
        @SerialName("CardImageUrl")
        val cardImageUrl: String? = "",
        @SerialName("CardNumber")
        val cardNumber: String? = "",
        @SerialName("CardPin")
        val cardPin: String? = "",
        @SerialName("CreatedDate")
        val createdDate: String? = "",
        @SerialName("DashPaid")
        val dashPaid: Double? = 0.0,
        @SerialName("Id")
        val id: Int? = 0,
        @SerialName("IsActive")
        val isActive: Boolean? = false,
        @SerialName("IsEmpty")
        val isEmpty: Boolean? = false,
        @SerialName("LastTransactionDate")
        val lastTransactionDate: String? = "",
        @SerialName("LegalName")
        val legalName: String? = "",
        @SerialName("LogoUrl")
        val logoUrl: String? = "",
        @SerialName("MerchantId")
        val merchantId: Int? = 0,
        @SerialName("MerchantLocationId")
        val merchantLocationId: Int? = 0,
        @SerialName("PaymentInstructions")
        val paymentInstructions: String? = "",
        @SerialName("PaymentSourceType")
        val paymentSourceType: Int? = 0,
        @SerialName("PromoAmount")
        val promoAmount: Double? = 0.0,
        @SerialName("PurchaseAmount")
        val purchaseAmount: Double? = 0.0,
        @SerialName("RedeemedOn")
        val redeemedOn: String? = "",
        @SerialName("RemainingAmount")
        val remainingAmount: Double? = 0.0,
        @SerialName("Savings")
        val savings: Double? = 0.0,
        @SerialName("SharedOn")
        val sharedOn: Any? = Any(),
        @SerialName("SharedTo")
        val sharedTo: String? = "",
        @SerialName("SharedToEmail")
        val sharedToEmail: String? = "",
        @SerialName("SystemName")
        val systemName: String? = "",
        @SerialName("Tip")
        val tip: Double? = 0.0,
        @SerialName("TotalDashTransactionAmount")
        val totalDashTransactionAmount: Any? = Any(),
        @SerialName("TotalTransactionAmount")
        val totalTransactionAmount: Double? = 0.0,
        @SerialName("USDPaid")
        val uSDPaid: Double? = 0.0,
        @SerialName("WasShared")
        val wasShared: Boolean? = false
    ) {
        @Serializable
        data class CardHistory(
            @SerialName("Action")
            val action: String? = "",
            @SerialName("CreatedDate")
            val createdDate: String? = "",
            @SerialName("UpdatedAmount")
            val updatedAmount: Double? = 0.0
        )
    }
}
