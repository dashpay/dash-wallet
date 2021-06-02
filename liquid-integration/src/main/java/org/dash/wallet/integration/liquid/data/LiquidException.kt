package org.dash.wallet.integration.liquid.data

class LiquidException(error: String, message: String, val code: Int) : Exception("$error: message: $message code:$code")