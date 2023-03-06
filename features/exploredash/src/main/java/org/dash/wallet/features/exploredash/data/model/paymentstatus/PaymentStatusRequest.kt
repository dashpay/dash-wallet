package org.dash.wallet.features.exploredash.data.model.paymentstatus

import com.google.gson.annotations.SerializedName

data class PaymentStatusRequest(@SerializedName("order_id") val orderId: String? = "", val paymentId: String? = "")
