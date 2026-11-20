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
import java.util.Locale

class AmountEntryInputTest {

    private fun type(keys: List<String>, maxDecimalPlaces: Int = 2, start: String = "0"): String =
        keys.fold(start) { current, key -> processAmountKeyInput(current, key, maxDecimalPlaces) }

    @Test
    fun `first digit replaces the leading zero`() {
        assertEquals("7", type(listOf("7")))
        assertEquals("70", type(listOf("7", "0")))
    }

    @Test
    fun `zero stays a single zero`() {
        assertEquals("0", type(listOf("0", "0")))
    }

    @Test
    fun `decimal point is added once`() {
        assertEquals("0.", type(listOf(".")))
        assertEquals("1.5", type(listOf("1", ".", ".", "5")))
    }

    @Test
    fun `digits beyond maxDecimalPlaces are rejected`() {
        assertEquals("1.23", type(listOf("1", ".", "2", "3", "4")))
        assertEquals("1.23456789", type(listOf("1", ".", "2", "3", "4", "5", "6", "7", "8", "9", "1"), 8))
    }

    @Test
    fun `back removes the last character and floors to zero`() {
        assertEquals("1.", type(listOf("back"), start = "1.5"))
        assertEquals("0", type(listOf("back"), start = "5"))
        assertEquals("0", type(listOf("back"), start = "0"))
    }

    @Test
    fun `back_long resets to zero`() {
        assertEquals("0", type(listOf("back_long"), start = "123.45"))
    }

    @Test
    fun `display uses the locale decimal separator and keeps a trailing one`() {
        val german = decimalSeparatorFor(Locale.GERMANY)
        assertEquals(',', german)
        assertEquals("125,50", localizeAmountForDisplay("125.50", german))
        assertEquals("12,", localizeAmountForDisplay("12.", german))

        val us = decimalSeparatorFor(Locale.US)
        assertEquals('.', us)
        assertEquals("125.50", localizeAmountForDisplay("125.50", us))
    }
}
