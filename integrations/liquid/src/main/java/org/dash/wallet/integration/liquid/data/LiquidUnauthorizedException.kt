package org.dash.wallet.integration.liquid.data

class LiquidUnauthorizedException (error: String, message: String, code: Int) : LiquidException(error, message, code)