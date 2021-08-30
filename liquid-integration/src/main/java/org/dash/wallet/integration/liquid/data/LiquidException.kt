package org.dash.wallet.integration.liquid.data

open class LiquidException(error: String, message: String, val code: Int) : Exception("$error: message: $message code:$code")