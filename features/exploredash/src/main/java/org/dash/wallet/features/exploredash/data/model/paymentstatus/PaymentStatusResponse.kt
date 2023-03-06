package org.dash.wallet.features.exploredash.data.model.paymentstatus

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
