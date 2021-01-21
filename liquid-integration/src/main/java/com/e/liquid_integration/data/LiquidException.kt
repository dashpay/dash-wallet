package com.e.liquid_integration.data

class LiquidException(error: String, message: String, val code: Int) : Exception("$error: message: $message code:$code")