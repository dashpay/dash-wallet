package org.dash.android.lightpayprot.data

data class SimplifiedPayment(
        var merchantData: String,
        val transaction: String,
        val refundTo: String,
        val memo: String
)
