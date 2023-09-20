package org.dash.wallet.integrations.maya.model

data class ExchangeRateResponse(
    val motd: Motd,
    val success: Boolean,
    val base: String,
    val date: String,
    val rates: Map<String, Double>
)

data class Motd(
    val msg: String,
    val url: String
)
