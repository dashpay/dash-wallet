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

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputRulesTest {
    /** Presses the keys the way the keypad does: digits, and '.' for the separator key. */
    private fun type(keys: String, fiatLimit: Int? = null, isDashAmount: Boolean = false): String {
        val maxDecimals = AmountInputRules.maxDecimals(isDashAmount, fiatLimit)
        val input = StringBuilder()
        keys.forEach { key ->
            if (key == '.') {
                if (AmountInputRules.acceptsSeparator(input.toString(), '.', maxDecimals)) {
                    input.append('.')
                }
            } else if (AmountInputRules.acceptsDigit(input.toString(), '.', maxDecimals)) {
                input.append(key)
            }
        }
        return input.toString()
    }

    @Test
    fun withoutALimit_fiatStopsAtTwoDecimalsWhateverTheCurrency() {
        // Send and Receive never set a limit, also when the wallet's currency is yen or dinar
        assertEquals("12.34", type("12.3456"))
        assertEquals("12.34", type("12.3.456"))
    }

    @Test
    fun dash_takesEightDecimalsWhateverTheLimit() {
        assertEquals("0.12345678", type("0.123456789", isDashAmount = true))
        assertEquals("0.12345678", type("0.123456789", fiatLimit = 0, isDashAmount = true))
    }

    @Test
    fun limitOfThree_takesAThirdDecimal() {
        assertEquals("1.234", type("1.2345", fiatLimit = 3))
    }

    @Test
    fun limitOfZero_ignoresTheSeparatorKey() {
        assertEquals("1500", type("15.00", fiatLimit = 0))
    }
}
