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

import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.integrations.coinbase.viewmodels.AmountMode
import org.dash.wallet.integrations.coinbase.viewmodels.TransferAmount
import org.dash.wallet.integrations.coinbase.viewmodels.DASH_MAX_DECIMALS
import org.dash.wallet.integrations.coinbase.viewmodels.FIAT_MAX_DECIMALS
import org.dash.wallet.integrations.coinbase.viewmodels.CANONICAL_SEPARATOR
import org.dash.wallet.integrations.coinbase.viewmodels.canonicalizeSeparator
import org.dash.wallet.integrations.coinbase.viewmodels.amountCurrencySpan
import org.dash.wallet.integrations.coinbase.viewmodels.amountMode
import org.dash.wallet.integrations.coinbase.viewmodels.decimalCount
import org.dash.wallet.integrations.coinbase.viewmodels.transferAmount
import org.dash.wallet.integrations.coinbase.viewmodels.truncateToDecimals
import org.dash.wallet.integrations.coinbase.viewmodels.wouldExceedDecimals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * MO-995. The transfer screen used to derive the amount text, the span that styles its
 * currency label and the keypad's decimal limit from three different places, keyed off the
 * currency picker's asynchronously loaded LABEL. These cover the four reported symptoms:
 * the setSpan crash (A), the frozen keypad (B), the stale USD label (C) and the USD label
 * on a local-currency figure (D).
 */
class TransferAmountFormatTest {
    companion object {
        private const val SEPARATOR = '.'
        private const val BYN = "BYN"

        /** Same shape as Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode(). */
        private val FIAT_FORMAT: MonetaryFormat = MonetaryFormat()
            .withLocale(Locale.US)
            .minDecimals(2)
            .optionalDecimals()
            .noCode()
    }

    private fun fiat(value: String, currency: String = BYN) = transferAmount(
        value = value,
        isFiat = true,
        localCurrencyCode = currency,
        decimalSeparator = SEPARATOR,
        fiatFormat = FIAT_FORMAT
    )

    private fun dash(value: String, currency: String = BYN) = transferAmount(
        value = value,
        isFiat = false,
        localCurrencyCode = currency,
        decimalSeparator = SEPARATOR,
        fiatFormat = FIAT_FORMAT
    )

    /**
     * Asserts the span styles exactly the currency label -- whatever remains of the text once
     * the digits are removed. Placement-agnostic: a locale may put the symbol on either side.
     */
    private fun assertSpansOnlyTheCurrencyLabel(amount: TransferAmount) {
        val span = amount.currencySpan!!
        assertEquals(
            "the span must cover the label and nothing but the label",
            amount.amountPart,
            amount.text.removeRange(span.from, span.to)
        )
    }

    // ---------------------------------------------------------------- mode selection (C, D)

    @Test
    fun `mode follows the picker's boolean, never a currency label`() {
        assertEquals(AmountMode.FIAT, amountMode(isFiatSelected = true))
        assertEquals(AmountMode.DASH, amountMode(isFiatSelected = false))
    }

    @Test
    fun `fiat mode with a non-USD local currency labels in that currency, not USD`() {
        // C and D: the picker label used to be the seeded "USD" while the real currency was
        // BYN, so the label never matched and a BYN amount was rendered as "<figure> USD".
        val amount = fiat("12.34")

        assertEquals(AmountMode.FIAT, amount.mode)
        assertFalse("must not label a BYN amount as USD", amount.text.contains("USD"))
        assertTrue(amount.text.contains("12.34"))
    }

    @Test
    fun `a currency emission that never arrives leaves the default seeded code, still in fiat mode`() {
        // The absent-emission case: localCurrencyCode is still the USD default. The mode must
        // stay FIAT anyway -- that is exactly what the old label comparison got wrong in
        // reverse, and it is what keeps the decimal limit and the span bounds consistent.
        val amount = fiat("12.34", currency = "USD")

        assertEquals(AmountMode.FIAT, amount.mode)
        assertEquals(FIAT_MAX_DECIMALS, amount.maxDecimals)
        assertNotNull(amount.currencySpan)
    }

    @Test
    fun `dash mode labels in DASH whatever the local currency is`() {
        val amount = dash("1.5")

        assertEquals(AmountMode.DASH, amount.mode)
        assertEquals("1.5 DASH", amount.text)
        assertEquals("1.5", amount.amountPart)
    }

    // ------------------------------------------------------------------- span bounds (A)

    @Test
    fun `span covers the trailing currency label and nothing else`() {
        val amount = dash("1.5")
        val span = amount.currencySpan!!

        assertEquals("1.5".length, span.from)
        assertEquals(amount.text.length, span.to)
        assertEquals(" DASH", amount.text.substring(span.from, span.to))
    }

    @Test
    fun `span is measured against the formatted amount, not the raw input`() {
        // A: the crash. A conversion hands in 8 decimals; the fiat branch renders 2. Bounds
        // taken from the raw 8-decimal input overshot the text and setSpan threw.
        val amount = fiat("0.00123456")

        assertTrue("raw input is longer than the rendered figure", "0.00123456".length > amount.amountPart.length)
        val span = amount.currencySpan!!
        assertTrue("from must not overshoot to", span.from < span.to)
        assertTrue("to must stay inside the text", span.to <= amount.text.length)
        assertSpansOnlyTheCurrencyLabel(amount)

        // Measured against the raw input instead, the bounds no longer describe the text --
        // which is what made setSpan throw.
        assertNull(amountCurrencySpan(amount.text, "0.00123456", isCurrencyFirst = false))
    }

    @Test
    fun `span degrades to null rather than producing out-of-range bounds`() {
        // The belt: a mismatched amountPart must yield no styling, never a crash.
        assertNull(amountCurrencySpan("0.00 Br", "0.00123456", isCurrencyFirst = false))
        assertNull(amountCurrencySpan("0.00 Br", "", isCurrencyFirst = false))
        assertNull(amountCurrencySpan("", "0.00", isCurrencyFirst = false))
        assertNull(amountCurrencySpan("0.00", "0.00", isCurrencyFirst = false))
    }

    @Test
    fun `currency-first text styles the leading symbol`() {
        val span = amountCurrencySpan("$ 12.34", "12.34", isCurrencyFirst = true)!!

        assertEquals(0, span.from)
        assertEquals(2, span.to)
        assertEquals("$ ", "$ 12.34".substring(span.from, span.to))
    }

    @Test
    fun `every span the formatter publishes is a valid setSpan range`() {
        val inputs = listOf("", "0", "0.", "0.0", "0.00", "12", "12.3", "12.34", "0.00123456", "1234.5678")
        for (input in inputs) {
            for (isFiat in listOf(true, false)) {
                val amount = transferAmount(input, isFiat, BYN, SEPARATOR, FIAT_FORMAT)
                amount.currencySpan?.let { span ->
                    assertTrue("$input/$isFiat: from >= 0", span.from >= 0)
                    assertTrue("$input/$isFiat: from < to", span.from < span.to)
                    assertTrue("$input/$isFiat: to <= length", span.to <= amount.text.length)
                }
            }
        }
    }

    // -------------------------------------------------------------- decimal gating (B)

    @Test
    fun `decimalCount counts digits after the separator`() {
        assertEquals(0, decimalCount("12", SEPARATOR))
        assertEquals(0, decimalCount("12.", SEPARATOR))
        assertEquals(1, decimalCount("12.3", SEPARATOR))
        assertEquals(8, decimalCount("0.00123456", SEPARATOR))
    }

    @Test
    fun `keypad accepts up to two decimals in fiat mode`() {
        assertFalse(wouldExceedDecimals("12", SEPARATOR, FIAT_MAX_DECIMALS))
        assertFalse(wouldExceedDecimals("12.", SEPARATOR, FIAT_MAX_DECIMALS))
        assertFalse(wouldExceedDecimals("12.3", SEPARATOR, FIAT_MAX_DECIMALS))
        assertTrue(wouldExceedDecimals("12.34", SEPARATOR, FIAT_MAX_DECIMALS))
    }

    @Test
    fun `keypad accepts up to eight decimals in dash mode`() {
        assertFalse(wouldExceedDecimals("0.0012345", SEPARATOR, DASH_MAX_DECIMALS))
        assertTrue(wouldExceedDecimals("0.00123456", SEPARATOR, DASH_MAX_DECIMALS))
    }

    @Test
    fun `a dash-scale amount is never gated on the fiat limit`() {
        // B: the frozen keypad. The text carried 8 decimals while the gate used 2, so every
        // digit press returned early. The limit now comes from the mode that made the text.
        val amount = dash("0.00123456")

        assertEquals(DASH_MAX_DECIMALS, amount.maxDecimals)
        assertFalse(
            "a dash amount must not be gated at 2 places",
            wouldExceedDecimals(amount.amountPart.dropLast(1), SEPARATOR, amount.maxDecimals)
        )
    }

    // --------------------------------------------- comma-decimal locales (separator)

    /**
     * The device locale's separator is a DISPLAY concern. The keypad writes '.', and so must
     * every value that is counted, gated or parsed -- otherwise on a comma-decimal locale
     * (de, fr, es, it, pt, nl, pl, ru, be_BY ...) the gate looks for a ',' that is never
     * there, finds no separator, and lets a fiat amount grow to 8 decimal places.
     */
    @Test
    fun `the decimal gate keys off the separator the keypad inserts`() {
        // A comma-locale user typing 12.34 writes '.', not ','.
        assertTrue(wouldExceedDecimals("12.34", CANONICAL_SEPARATOR, FIAT_MAX_DECIMALS))
        // Gating that same text on the locale's ',' finds nothing and waves it through.
        assertFalse(wouldExceedDecimals("12.34", ',', FIAT_MAX_DECIMALS))
    }

    @Test
    fun `fiat is held to two decimals on a comma-decimal locale`() {
        // decimalSeparator = ',' (the device locale), input written with '.' by the keypad.
        val amount = transferAmount("12.999999", true, BYN, ',', FIAT_FORMAT)

        assertEquals("12.99", amount.inputValue)
        assertEquals("12.99", amount.amountPart)
        assertEquals(FIAT_MAX_DECIMALS, amount.maxDecimals)
    }

    @Test
    fun `a partial dash entry survives on a comma-decimal locale`() {
        // Counting "0.0" with ',' saw no fraction and sent it through the BigDecimal path,
        // where stripTrailingZeros collapsed it to "0" and swallowed the user's decimal
        // point. Counting canonically preserves it.
        assertEquals("0.0 DASH", transferAmount("0.0", false, BYN, ',', FIAT_FORMAT).text)
        assertEquals("0. DASH", transferAmount("0.", false, BYN, ',', FIAT_FORMAT).text)
    }

    @Test
    fun `everything the formatter publishes is parseable regardless of locale`() {
        // amountPart re-enters the keypad and inputValue reaches BigDecimal, Coin.parseCoin
        // and Fiat.parseFiat. A locale separator in either would break all three.
        val inputs = listOf("0", "0.", "0.0", "12", "12.3", "12.34", "12.999999", "1234.5678")
        for (sep in listOf('.', ',')) {
            for (input in inputs) {
                for (isFiat in listOf(true, false)) {
                    val amount = transferAmount(input, isFiat, BYN, sep, FIAT_FORMAT)
                    assertFalse(
                        "amountPart '${amount.amountPart}' ($input/$isFiat/sep=$sep) must not carry a comma",
                        amount.amountPart.contains(',')
                    )
                    assertFalse(
                        "inputValue '$${amount.inputValue}' ($input/$isFiat/sep=$sep) must not carry a comma",
                        amount.inputValue.contains(',')
                    )
                }
            }
        }
    }

    @Test
    fun `canonicalizeSeparator rewrites only the decimal mark and keeps the length`() {
        assertEquals("12.34", canonicalizeSeparator("12,34", ','))
        assertEquals("12.34", canonicalizeSeparator("12.34", '.'))
        assertEquals("12,34".length, canonicalizeSeparator("12,34", ',').length)
    }

    @Test
    fun `a localised fiat figure is canonicalised before the keypad reads it back`() {
        // MonetaryFormat with a comma locale renders "12,34"; left as-is it would reach
        // BigDecimal and Fiat.parseFiat, which both reject a comma.
        val commaFormat = MonetaryFormat()
            .withLocale(Locale.GERMANY)
            .minDecimals(2)
            .optionalDecimals()
            .noCode()
        val amount = transferAmount("12.34", true, "EUR", ',', commaFormat)

        assertEquals("12.34", amount.amountPart)
        assertNotNull(amount.amountPart.toBigDecimalOrNull())
        assertSpansOnlyTheCurrencyLabel(amount)
    }

    // ------------------------------------------------------- fiat conversion overflow

    @Test
    fun `a conversion finer than two decimals is truncated, not rounded up`() {
        assertEquals("0.00", truncateToDecimals("0.00123456", FIAT_MAX_DECIMALS))
        assertEquals("12.99", truncateToDecimals("12.999999", FIAT_MAX_DECIMALS))
        assertEquals("12.34", truncateToDecimals("12.34", FIAT_MAX_DECIMALS))
    }

    @Test
    fun `fiat mode re-scales an over-precise conversion and reports it back`() {
        // Fiat.parseFiat throws on anything finer than 4 places, and the keypad would refuse
        // every further digit while the text still showed 2. The input value handed back has
        // to be the re-scaled one, since the keypad and the transfer button read it again.
        val amount = fiat("12.999999")

        assertEquals("12.99", amount.inputValue)
        assertEquals("12.99", amount.amountPart)
        assertEquals(FIAT_MAX_DECIMALS, decimalCount(amount.inputValue, SEPARATOR))
        assertTrue(amount.text.contains("12.99"))
    }

    @Test
    fun `partial fiat input renders a zero instead of throwing`() {
        for (input in listOf("", "0", "0.")) {
            val amount = fiat(input)
            assertNotNull("[$input] must produce a fiat value", amount.fiatAmount)
        }
    }

    // -------------------------------------------------------------- round trip (B, C, D)

    @Test
    fun `fiat to dash to fiat round trip stays consistent`() {
        // The reported reproduction: switching fiat -> dash -> fiat left the screen with a
        // DASH-scale figure under a fiat label, an over-long span and a gate that refused
        // every digit. Each leg must now agree with itself.
        val first = fiat("12.34")
        assertEquals(AmountMode.FIAT, first.mode)
        assertEquals(FIAT_MAX_DECIMALS, first.maxDecimals)
        assertSpansOnlyTheCurrencyLabel(first)

        // dash leg, as the picker hands over a converted, 8-decimal value
        val second = dash("0.05432100")
        assertEquals(AmountMode.DASH, second.mode)
        assertEquals(DASH_MAX_DECIMALS, second.maxDecimals)
        // A full 8 decimals goes through the BigDecimal path, which strips trailing zeros --
        // master's behaviour, preserved.
        assertEquals("0.054321 DASH", second.text)
        assertSpansOnlyTheCurrencyLabel(second)

        // back to fiat, again with a converted value that is finer than cents
        val third = fiat("12.3456789")
        assertEquals(AmountMode.FIAT, third.mode)
        assertEquals(FIAT_MAX_DECIMALS, third.maxDecimals)
        assertEquals("12.34", third.inputValue)
        assertEquals("12.34", third.amountPart)
        assertFalse("must not carry the DASH label", third.text.contains("DASH"))
        assertFalse("must not carry a stale USD label", third.text.contains("USD"))
        assertSpansOnlyTheCurrencyLabel(third)
    }
}
