package org.dash.wallet.common.util

/**
 * data class to hold the fiat amount formatted as per the user's Locale
 */
data class FiatAmountFormat(val isAmountToLeftPosition: Boolean, val formattedAmount: String)
