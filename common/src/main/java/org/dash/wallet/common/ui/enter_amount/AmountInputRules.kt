/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.ui.enter_amount

import java.util.Currency

/** How many decimals the amount keypad accepts. */
object AmountInputRules {
    private const val DASH_DECIMALS = 8
    private const val DEFAULT_FIAT_DECIMALS = 2

    // Fiat cannot hold more
    private const val MAX_FIAT_DECIMALS = 4

    /** The fraction digits of the fiat currency being typed (JPY 0, USD 2, KWD 3), or of Dash. */
    fun maxDecimals(isDashAmount: Boolean, fiatCurrencyCode: String?): Int {
        if (isDashAmount) {
            return DASH_DECIMALS
        }
        val digits = try {
            Currency.getInstance(fiatCurrencyCode).defaultFractionDigits
        } catch (e: Exception) {
            DEFAULT_FIAT_DECIMALS
        }
        return if (digits < 0) DEFAULT_FIAT_DECIMALS else minOf(digits, MAX_FIAT_DECIMALS)
    }

    fun acceptsDigit(input: String, decimalSeparator: Char, maxDecimals: Int): Boolean {
        val separatorIndex = input.indexOf(decimalSeparator)
        return separatorIndex < 0 || input.length - separatorIndex <= maxDecimals
    }

    fun acceptsSeparator(input: String, decimalSeparator: Char, maxDecimals: Int): Boolean =
        maxDecimals > 0 && input.indexOf(decimalSeparator) < 0
}
