package org.dash.android.lightpayprot

data class Payment(
        var merchantData: String,
        val transaction: String,
        val refundTo: String,
        val memo: String
)
