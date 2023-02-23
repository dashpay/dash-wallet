package org.dash.wallet.features.exploredash.data.model.paymentstatus


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaymentStatusRequest(
    @SerialName("order_id")
    val orderId: String? = "",
    val paymentId: String? = ""
)