package org.dash.wallet.features.exploredash.data.model.paymentstatus

import kotlinx.serialization.SerialName

data class PaymentStatusResponse(

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
        @SerialName("Errors")
        val errors: List<String?>? = listOf(),
        @SerialName("gift_card_id")
        val giftCardId: Long? = 0,
        @SerialName("order_id")
        val orderId: String? = "",
        @SerialName("payment_id")
        val paymentId: String? = "",
        val status: String? = ""
    )
}
