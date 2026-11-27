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

import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

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

    private val plain = MonetaryFormat().withLocale(Locale.US).noCode().minDecimals(2).optionalDecimals()

    @Test
    fun fiatInput_hasNoMoreDecimalsThanTheCurrency() {
        // what switching the entry from Dash to fiat puts on the keypad
        assertEquals("1235", AmountInputRules.fiatInput(Fiat.parseFiat("JPY", "1234.5"), plain))
        assertEquals("1234", AmountInputRules.fiatInput(Fiat.parseFiat("KRW", "1234.4999"), plain))
        assertEquals("1.235", AmountInputRules.fiatInput(Fiat.parseFiat("KWD", "1.2346"), plain))
        assertEquals("1234.50", AmountInputRules.fiatInput(Fiat.parseFiat("USD", "1234.5"), plain))
        assertEquals("1234", AmountInputRules.fiatInput(Fiat.parseFiat("USD", "1234"), plain))
        assertEquals("1234.57", AmountInputRules.fiatInput(Fiat.parseFiat("EUR", "1234.5678"), plain))
    }

    @Test
    fun fiatInput_canBeTypedOn() {
        val yen = AmountInputRules.fiatInput(Fiat.parseFiat("JPY", "1234.5"), plain)

        assertEquals(yen + "0", yen + type("0", "JPY"))
        assertEquals(true, AmountInputRules.acceptsDigit(yen, '.', AmountInputRules.maxDecimals(false, "JPY")))
    }

    @Test
    fun fitDecimals_roundsOnlyAValueThatIsTooLong() {
        // an amount saved before the keypad knew the currency's decimals
        assertEquals("1235", AmountInputRules.fitDecimals("1234.5", 0))
        assertEquals("1.235", AmountInputRules.fitDecimals("1.2346", 3))
        assertEquals("12.5", AmountInputRules.fitDecimals("12.5", 2))
        assertEquals("12.50", AmountInputRules.fitDecimals("12.50", 2))
        assertEquals("0", AmountInputRules.fitDecimals("0", 0))
        assertEquals("abc", AmountInputRules.fitDecimals("abc", 2))
    }
}
