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

package org.dash.wallet.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal

/**
 * [GenericUtils.formatFiatWithoutComma] normalises a human-formatted amount into something
 * BigDecimal, Coin.parseCoin and Fiat.parseFiat accept. Everything it returns is parsed and
 * a good deal of it is then sent to an exchange, so a mis-read separator is a wrong amount,
 * not a cosmetic glitch.
 */
class FormatFiatWithoutCommaTest {
    private fun format(value: String) = GenericUtils.formatFiatWithoutComma(value)

    // ------------------------------------------------ unambiguous: two kinds of separator

    @Test
    fun `european grouping with a comma decimal reads as one number`() {
        // The regression this test exists for: the old rule was "contains a dot AND a
        // comma, so strip the commas", turning 1234.56 into 1.23456 -- off by 1000x.
        assertEquals("1234.56", format("1.234,56"))
        assertEquals("1234567.89", format("1.234.567,89"))
    }

    @Test
    fun `us grouping with a dot decimal reads as one number`() {
        assertEquals("1234.56", format("1,234.56"))
        assertEquals("1234567.89", format("1,234,567.89"))
    }

    @Test
    fun `the later separator is the decimal mark`() {
        // Grouping marks always precede the decimal mark, so position decides it.
        assertEquals("1234.5", format("1,234.5"))
        assertEquals("1234.5", format("1.234,5"))
    }

    // ------------------------------------------- unambiguous: one kind, repeated = grouping

    @Test
    fun `a repeated separator is grouping, since a number has at most one decimal mark`() {
        assertEquals("1234567", format("1,234,567"))
        assertEquals("1234567", format("1.234.567"))
    }

    // -------------------------------------------------- ambiguous: one kind, one occurrence

    @Test
    fun `a single separator still reads as a decimal mark`() {
        // "1,234" is 1.234 in one locale and 1234 in another; nothing here can tell them
        // apart, so this keeps the long-standing reading rather than guessing a new one.
        assertEquals("12.34", format("12,34"))
        assertEquals("12.34", format("12.34"))
        assertEquals("1.234", format("1,234"))
        assertEquals("1.234", format("1.234"))
    }

    @Test
    fun `the arabic decimal separator is still handled`() {
        assertEquals("12.34", format("12٫34"))
    }

    // ---------------------------------------------------------------------- edge cases

    @Test
    fun `values without separators are untouched`() {
        assertEquals("", format(""))
        assertEquals("0", format("0"))
        assertEquals("1234567", format("1234567"))
    }

    @Test
    fun `a trailing separator is preserved so partial input keeps rendering`() {
        assertEquals("0.", format("0."))
        assertEquals("0.", format("0,"))
    }

    @Test
    fun `the eight-decimal cap still applies`() {
        assertEquals("12.34567890", format("12.3456789012"))
        assertEquals("1234.56789012", format("1.234,56789012345"))
    }

    @Test
    fun `every result parses`() {
        val inputs = listOf(
            "1.234,56", "1,234.56", "1.234.567,89", "1,234,567.89",
            "1,234,567", "1.234.567", "12,34", "12.34", "0", "1234567"
        )
        for (input in inputs) {
            assertNotNull("[$input] must produce a parseable number", BigDecimal(format(input)))
        }
    }
}
