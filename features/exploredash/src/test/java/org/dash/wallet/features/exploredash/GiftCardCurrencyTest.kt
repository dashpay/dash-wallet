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

package org.dash.wallet.features.exploredash

import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.dash.wallet.features.exploredash.utils.toCardFormattedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class GiftCardCurrencyTest {
    private val originalLocale = Locale.getDefault()
    private val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    private val originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)

    private fun digits(text: String) = text.filter(Char::isDigit).map { Character.digit(it, 10) }.joinToString("")

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
    }

    @Test
    fun toFiat_keepsZeroAndThreeDecimalCurrenciesExact() {
        val cases = listOf(
            Triple(1000.0, "JPY", "1000"),
            Triple(1.234, "KWD", "1.234"),
            Triple(19.99, "GBP", "19.99"),
            Triple(12345678.0, "JPY", "12345678")
        )

        cases.forEach { (amount, currency, expected) ->
            val fiat = GiftCardCurrency.toFiat(amount, currency)
            assertEquals(currency, fiat.currencyCode)
            assertEquals("$amount $currency", expected, fiat.toPlainString())
        }
    }

    @Test
    fun normalize_acceptsOnlyRealCurrencies() {
        assertEquals("GBP", GiftCardCurrency.normalize("GBP"))
        assertEquals("GBP", GiftCardCurrency.normalize(" gbp "))
        listOf(null, "", "   ", "US", "DOLLARS", "ZZZ", "XAU").forEach {
            assertNull("'$it'", GiftCardCurrency.normalize(it))
        }
    }

    @Test
    fun display_usesTheCurrencysOwnDecimals() {
        Locale.setDefault(Locale.US)

        assertEquals("$25.00", GiftCardCurrency.displayFormat("USD").format(25.0))
        assertEquals("$25", GiftCardCurrency.displayFormat("USD", trimZeroDecimals = true).format(25.0))
        assertEquals("£19.99", GiftCardCurrency.displayFormat("GBP").format(19.99))
        assertEquals("¥1,000", GiftCardCurrency.displayFormat("JPY").format(1000.0))
        assertEquals("KWD1.234", GiftCardCurrency.displayFormat("KWD").format(1.234).replace(" ", ""))

        // the shared MonetaryFormat keeps the locale it was first loaded with, so compare digits only
        assertEquals("2500", digits(GiftCardCurrency.toFiat(25.0, "USD").toCardFormattedString()))
        assertEquals("1000", digits(GiftCardCurrency.toFiat(1000.0, "JPY").toCardFormattedString()))
        assertEquals("1234", digits(GiftCardCurrency.toFiat(1.234, "KWD").toCardFormattedString()))
        assertEquals("1900", digits(GiftCardCurrency.toFiat(18.991, "GBP").toCardFormattedString(roundUp = true)))
    }

    @Test
    fun discountedTotal_roundsUpOnceAtTheCurrencyDigits() {
        // 1.1 * 0.9 is 0.9900000000000001 in binary floating point, which must not become 0.991
        assertEquals("0.990", GiftCardCurrency.discountedTotal(mapOf(1.1 to 1), "KWD") { 0.1 }.toPlainString())
        assertEquals("22.50", GiftCardCurrency.discountedTotal(mapOf(25.0 to 1), "USD") { 0.1 }.toPlainString())
        // 18.9905 is rounded up, never down
        assertEquals("19.00", GiftCardCurrency.discountedTotal(mapOf(19.99 to 1), "USD") { 0.05 }.toPlainString())
        assertEquals("965", GiftCardCurrency.discountedTotal(mapOf(1000.0 to 1), "JPY") { 0.035 }.toPlainString())
        assertEquals("25.00", GiftCardCurrency.discountedTotal(mapOf(25.0 to 1), "USD") { 0.0 }.toPlainString())
    }

    @Test
    fun discountedTotal_addsEveryCardAtItsOwnDiscountBeforeRounding() {
        val discounts = mapOf(25.0 to 0.1, 33.33 to 0.035)
        val order = mapOf(25.0 to 2, 33.33 to 3)

        // 2 * 22.5 + 3 * 32.163450 = 141.49035
        val total = GiftCardCurrency.discountedTotal(order, "USD") { discounts.getValue(it) }
        assertEquals("141.50", total.toPlainString())
    }
}
