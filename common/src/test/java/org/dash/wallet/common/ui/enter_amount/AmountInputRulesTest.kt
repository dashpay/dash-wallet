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
    private fun type(keys: String, currencyCode: String?, isDashAmount: Boolean = false): String {
        val maxDecimals = AmountInputRules.maxDecimals(isDashAmount, currencyCode)
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
    fun twoDecimalCurrencies_stopAtTwoDecimalsAsBefore() {
        assertEquals("12.34", type("12.3456", "USD"))
        assertEquals("12.34", type("12.3456", "EUR"))
        assertEquals("12.34", type("12.3.456", "GBP"))
    }

    @Test
    fun kuwaitiDinar_takesAThirdDecimal() {
        assertEquals("1.234", type("1.2345", "KWD"))
        assertEquals("1.234", type("1.2345", "BHD"))
        assertEquals("1.234", type("1.2345", "OMR"))
    }

    @Test
    fun yen_ignoresTheSeparatorKey() {
        assertEquals("1500", type("15.00", "JPY"))
    }

    @Test
    fun dash_takesEightDecimals() {
        assertEquals("0.12345678", type("0.123456789", "USD", isDashAmount = true))
    }

    @Test
    fun unknownCurrency_fallsBackToTwoDecimals() {
        assertEquals(2, AmountInputRules.maxDecimals(false, "NOPE"))
        assertEquals(2, AmountInputRules.maxDecimals(false, null))
        // XXX reports -1 fraction digits
        assertEquals(2, AmountInputRules.maxDecimals(false, "XXX"))
    }

    @Test
    fun valueLongerThanTheLimit_takesNoDigitUntilItIsShortened() {
        // what switching from Dash to yen can leave on the keypad
        val maxDecimals = AmountInputRules.maxDecimals(false, "JPY")

        assertEquals(false, AmountInputRules.acceptsDigit("1234.50", '.', maxDecimals))
        assertEquals(false, AmountInputRules.acceptsDigit("1234.", '.', maxDecimals))
        assertEquals(true, AmountInputRules.acceptsDigit("1234", '.', maxDecimals))
    }

    @Test
    fun callerLimit_canOnlyLowerTheFiatDecimals() {
        assertEquals(0, AmountInputRules.maxDecimals(false, "USD", fiatLimit = 0))
        assertEquals(2, AmountInputRules.maxDecimals(false, "USD", fiatLimit = 5))
        assertEquals(2, AmountInputRules.maxDecimals(false, "USD", fiatLimit = null))
        assertEquals(8, AmountInputRules.maxDecimals(true, "USD", fiatLimit = 0))
    }
}
