package org.dash.android.lightpayprot.data

data class SimplifiedPaymentAck(
        var payment: SimplifiedPayment,
        val memo: String,
        val error: Int)
