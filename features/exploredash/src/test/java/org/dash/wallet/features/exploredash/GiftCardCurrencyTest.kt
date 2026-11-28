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

import kotlinx.coroutines.runBlocking
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Brand
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Giftcard
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.GiftcardResponse
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsApi
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.dash.wallet.features.exploredash.utils.toCardFormattedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.random.Random

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
    fun ctxTotal_isWhatCtxChargedForTheseCards() {
        // 5.55 at 1.6%: the discount 0.0888 becomes 0.09, where rounding the total up gave 5.47
        assertEquals("5.46", GiftCardCurrency.ctxTotal(mapOf(5.55 to 1), "GBP") { 160 / 10000.0 }.toPlainString())
        assertEquals("7.65", GiftCardCurrency.ctxTotal(mapOf(7.89 to 1), "EUR") { 300 / 10000.0 }.toPlainString())
        assertEquals("95.00", GiftCardCurrency.ctxTotal(mapOf(100.0 to 1), "GBP") { 500 / 10000.0 }.toPlainString())
        assertEquals("965", GiftCardCurrency.ctxTotal(mapOf(1000.0 to 1), "JPY") { 350 / 10000.0 }.toPlainString())
        assertEquals("1.211", GiftCardCurrency.ctxTotal(mapOf(1.234 to 1), "KWD") { 190 / 10000.0 }.toPlainString())
        assertEquals("25.00", GiftCardCurrency.ctxTotal(mapOf(25.0 to 1), "USD") { 0.0 }.toPlainString())
    }

    // spend-api: Money.Split -> MultiplyBig, on 90-bit floats that round away from zero at every step
    private fun chargedByCtx(minorUnits: Long, digits: Int, basisPoints: Int): Long {
        val precision = MathContext(28, RoundingMode.UP)
        val scale = BigDecimal.TEN.pow(digits)
        val major = BigDecimal(minorUnits).divide(scale, precision)
        val share = major.multiply(BigDecimal(basisPoints.toDouble() / 10000), precision)
        val discount = share.multiply(scale, precision).add(BigDecimal("0.5"), precision).toBigInteger()
        return minorUnits - discount.toLong()
    }

    @Test
    fun ctxTotal_followsTheServerRuleAtEveryCurrencyPrecision() {
        val random = Random(20261128)
        mapOf("JPY" to 0, "GBP" to 2, "KWD" to 3).forEach { (currency, digits) ->
            repeat(20_000) {
                val minorUnits = random.nextLong(1, 2_000_000)
                val basisPoints = random.nextInt(0, 5001)
                val value = BigDecimal(minorUnits).movePointLeft(digits)
                val total = GiftCardCurrency.ctxTotal(mapOf(value.toDouble() to 1), currency) {
                    basisPoints.toDouble() / 10000
                }
                assertEquals(
                    "$value $currency at $basisPoints",
                    chargedByCtx(minorUnits, digits, basisPoints),
                    total.movePointRight(digits).longValueExact()
                )
            }
        }
        // exact halves: 0.50 at 1% and at 3%, whose doubles lie on either side of the decimal
        listOf(100, 300, 500, 2500).forEach { basisPoints ->
            val total = GiftCardCurrency.ctxTotal(mapOf(0.5 to 1), "GBP") { basisPoints.toDouble() / 10000 }
            assertEquals("at $basisPoints", chargedByCtx(50, 2, basisPoints), total.movePointRight(2).longValueExact())
        }
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

    @Test
    fun discountedTotal_ofAPiggyCardsCardIsNotPushedUpACentByFloatNoise() = runBlocking {
        val card = Giftcard(
            id = 1,
            name = "Acme",
            description = "",
            image = "",
            priceType = "range",
            currency = "USD",
            discountPercentage = 5.6,
            minDenomination = 10.0,
            maxDenomination = 500.0,
            denomination = "",
            fee = 0,
            quantity = 1,
            brandId = 42
        )
        val api = mock<PiggyCardsApi> {
            onBlocking { getBrands(any()) } doReturn listOf(Brand("42", "Acme"))
            onBlocking { getGiftCards(any(), any()) } doReturn GiftcardResponse(200, "", listOf(card))
        }
        val piggyCards = PiggyCardsRepository(api, mock())
        piggyCards.getMerchant("42")
        // 5.6% less the 1.5% service fee is 0.040999999999999995 in the repository's arithmetic
        val discount = piggyCards.getGiftCardDiscount("42", 100.0)
        assertTrue(discount < 0.041)

        val total = GiftCardCurrency.discountedTotal(mapOf(100.0 to 1), "USD") { discount }

        assertEquals("95.90", total.toPlainString())
    }

    @Test
    fun isCardAmount_readsTheDecimalsAsWritten() {
        assertTrue(GiftCardCurrency.isCardAmount("10.00".toBigDecimal(), "USD"))
        assertFalse(GiftCardCurrency.isCardAmount("10.00001".toBigDecimal(), "USD"))
        assertFalse(GiftCardCurrency.isCardAmount("1000.5".toBigDecimal(), "JPY"))
        assertTrue(GiftCardCurrency.isCardAmount("1.234".toBigDecimal(), "KWD"))
        assertFalse(GiftCardCurrency.isCardAmount("-5".toBigDecimal(), "USD"))
        assertFalse(GiftCardCurrency.isCardAmount("92233720369".toBigDecimal(), "USD"))
        assertTrue(GiftCardCurrency.isCardAmount("92233720368".toBigDecimal(), "USD"))
        // no currency: only what a Fiat can hold
        assertTrue(GiftCardCurrency.isCardAmount("1.2345".toBigDecimal(), null))
        assertFalse(GiftCardCurrency.isCardAmount("1.123456789".toBigDecimal(), null))
    }
}
