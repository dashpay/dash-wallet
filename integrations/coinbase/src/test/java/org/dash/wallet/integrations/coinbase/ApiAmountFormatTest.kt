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

package org.dash.wallet.integrations.coinbase

import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.RoundingMode
import java.util.Locale

/**
 * The amount strings in Coinbase request bodies must be plain decimals.
 *
 * The buy flow formatted them with `Constants.SEND_PAYMENT_LOCAL_FORMAT`, which is built
 * with `withLocale(deviceLocale)` and therefore renders "12,34" on every comma-decimal
 * locale -- straight into a bank deposit and a buy order.
 */
class ApiAmountFormatTest {
    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    private fun wireAmount(value: String): String =
        CoinbaseConstants.API_AMOUNT_FORMAT
            .roundingMode(RoundingMode.UP)
            .format(Fiat.parseFiat("USD", value))
            .toString()

    @Test
    fun `the wire amount is a plain decimal on every locale`() {
        val locales = listOf(
            Locale.US, Locale.UK, Locale.GERMANY, Locale.FRANCE, Locale("ru", "RU"), Locale("be", "BY")
        )
        for (locale in locales) {
            Locale.setDefault(locale)
            val formatted = wireAmount("12.34")
            assertEquals("wrong wire amount under $locale", "12.34", formatted)
            assertFalse("$locale must not produce a comma", formatted.contains(','))
        }
    }

    @Test
    fun `the wire amount always carries two decimals`() {
        Locale.setDefault(Locale.US)
        assertEquals("12.00", wireAmount("12"))
        assertEquals("12.50", wireAmount("12.5"))
        assertEquals("1234567.89", wireAmount("1234567.89"))
    }

    @Test
    fun `the wire amount is never grouped`() {
        // A thousands separator would be just as unparseable as a comma decimal mark.
        Locale.setDefault(Locale.GERMANY)
        val formatted = wireAmount("1234567.89")
        assertEquals("1234567.89", formatted)
        assertFalse(formatted.contains('.') && formatted.indexOf('.') != formatted.lastIndexOf('.'))
    }

    @Test
    fun `a MonetaryFormat without withLocale is canonical everywhere`() {
        // What the transfer screen's dashFormat relies on: values it produces are inputs to
        // BigDecimal and Coin.parseCoin, so they must carry a dot on every locale.
        val inputFormat = MonetaryFormat().noCode().minDecimals(6).optionalDecimals()
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale("be", "BY"))) {
            Locale.setDefault(locale)
            val formatted = inputFormat.format(Coin.parseCoin("1.5")).toString()
            assertEquals("wrong under $locale", "1.500000", formatted)
            assertNotNull("$locale must stay parseable", formatted.toBigDecimalOrNull())
        }
    }

    @Test
    fun `rounding is up, so a deposit always covers the order`() {
        Locale.setDefault(Locale.US)
        // Fiat holds 8 decimals in dashj; the wire amount rounds up to the next cent.
        assertEquals("12.35", wireAmount("12.341"))
    }
}
