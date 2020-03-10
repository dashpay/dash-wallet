package org.dash.android.lightpayprot

data class Output(
        var address: String,
        val script: String,
        val amount: Double,
        val description: String
)
