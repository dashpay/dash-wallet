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

/** How many decimals the amount keypad accepts. */
object AmountInputRules {
    private const val DASH_DECIMALS = 8
    private const val FIAT_DECIMALS = 2

    /** [fiatLimit] is for a screen whose fiat amounts do not have the usual two decimals. */
    fun maxDecimals(isDashAmount: Boolean, fiatLimit: Int? = null): Int =
        if (isDashAmount) DASH_DECIMALS else fiatLimit ?: FIAT_DECIMALS

    fun acceptsDigit(input: String, decimalSeparator: Char, maxDecimals: Int): Boolean {
        val separatorIndex = input.indexOf(decimalSeparator)
        return separatorIndex < 0 || input.length - separatorIndex <= maxDecimals
    }

    fun acceptsSeparator(input: String, decimalSeparator: Char, maxDecimals: Int): Boolean =
        maxDecimals > 0 && input.indexOf(decimalSeparator) < 0
}
