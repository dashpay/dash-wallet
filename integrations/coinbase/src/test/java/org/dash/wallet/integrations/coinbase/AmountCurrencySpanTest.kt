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

import org.dash.wallet.integrations.coinbase.ui.AmountCurrencySpan
import org.dash.wallet.integrations.coinbase.ui.amountCurrencySpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MO-995: tapping the fiat option on the Coinbase transfer screen crashed with
 * `IndexOutOfBoundsException: setSpan (12 ... 7) has end before start`.
 *
 * The span bounds are computed from separate mutable ViewModel fields and handed
 * straight to `Spannable.setSpan`, which throws on a reversed or out-of-bounds
 * range. These tests pin the bounds for each branch and the degrade-to-null
 * behaviour that keeps a field mismatch off the crash path.
 */
class AmountCurrencySpanTest {

    @Test
    fun dashSelected_spansTheTrailingCurrencyCode() {
        // applyNewValue returns "$formattedValue $monetaryCode".
        val span = amountCurrencySpan(
            text = "1.5 DASH",
            isDashSelected = true,
            formattedValue = "1.5",
            fiatBalance = "irrelevant on this branch",
            isCurrencyFirst = false
        )

        assertEquals(AmountCurrencySpan(3, 8), span)
    }

    @Test
    fun fiatCurrencyFirst_spansTheLeadingSymbol() {
        // applyNewValue returns "$symbol $fiatBalance".
        val span = amountCurrencySpan(
            text = "$ 4.52",
            isDashSelected = false,
            formattedValue = "stale",
            fiatBalance = "4.52",
            isCurrencyFirst = true
        )

        assertEquals(AmountCurrencySpan(0, 2), span)
    }

    @Test
    fun fiatCurrencyLast_spansTheTrailingSymbol() {
        // applyNewValue returns "$fiatBalance $symbol".
        val span = amountCurrencySpan(
            text = "4.52 Kč",
            isDashSelected = false,
            formattedValue = "stale",
            fiatBalance = "4.52",
            isCurrencyFirst = false
        )

        assertEquals(AmountCurrencySpan(4, 7), span)
    }

    /**
     * The regression itself, with the field lengths from the crash log.
     *
     * Switching DASH -> USD converts the amount via `formatInput`, so `inputValue`
     * becomes a many-decimal figure (12 chars) while `fiatBalance` is the 2-dp
     * formatted one. Using `inputValue.length` gave from=12 against a 7-char text.
     * The span must be bounded by `fiatBalance` instead, and must stay in range.
     */
    @Test
    fun fiatCurrencyLast_manyDecimalInput_staysInRange() {
        val text = "4.52 Kč" // length 7, as in the crash
        val span = amountCurrencySpan(
            text = text,
            isDashSelected = false,
            formattedValue = "stale",
            fiatBalance = "4.52",
            isCurrencyFirst = false
        )

        requireNotNull(span)
        assertEquals(4, span.from)
        assertEquals(7, span.to)
        // The exact invariant Spannable.setSpan enforces.
        assert(span.from in 0..span.to && span.to <= text.length)
    }

    @Test
    fun reversedBounds_degradeToNullInsteadOfCrashing() {
        // fiatBalance longer than the whole text — the shape that used to throw.
        val span = amountCurrencySpan(
            text = "4.52 Kč",
            isDashSelected = false,
            formattedValue = "stale",
            fiatBalance = "123456789.12",
            isCurrencyFirst = false
        )

        assertNull(span)
    }

    @Test
    fun emptyRange_degradesToNull() {
        // Nothing to style: the amount fills the whole text.
        val span = amountCurrencySpan(
            text = "1.5",
            isDashSelected = true,
            formattedValue = "1.5",
            fiatBalance = "",
            isCurrencyFirst = false
        )

        assertNull(span)
    }

    @Test
    fun currencyFirstWithNoRoomForASymbol_fallsBackAndDegradesToNull() {
        // text.length - fiatBalance.length == 0 sends this down the currency-last
        // branch, where from == to; that must be null, not a zero-width span.
        val span = amountCurrencySpan(
            text = "4.52",
            isDashSelected = false,
            formattedValue = "stale",
            fiatBalance = "4.52",
            isCurrencyFirst = true
        )

        assertNull(span)
    }
}
