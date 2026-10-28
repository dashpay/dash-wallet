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

package de.schildbach.wallet.ui.shielded

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.Fiat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Formatting helpers backing the More-screen balance cards. */
class ShieldedUiFormatTest {

    @Test
    fun `compact credits uses magnitude suffixes`() {
        // 1 DASH = 1e11 credits
        assertEquals("100B", Dash.parse("1").toCompactCreditsString())
        assertEquals("115.5B", Dash.parse("1.155").toCompactCreditsString())
        assertEquals("11.6T", Dash.parse("115.5").toCompactCreditsString())
        assertEquals("50M", Dash.parse("0.0005").toCompactCreditsString())
        assertEquals("1K", Dash.parse("0.00000001").toCompactCreditsString()) // 1 duff
        assertEquals("0", Dash.ZERO.toCompactCreditsString())
    }

    @Test
    fun `dash display string keeps two decimals minimum`() {
        assertEquals("2.00", Dash.parse("2").toDisplayString())
        assertEquals("115.50", Dash.parse("115.5").toDisplayString())
    }

    @Test
    fun `fiat display without code rounds to two decimals`() {
        // MO-1111: the amount-entry secondary line must round like the rest of the
        // app (e.g. the confirm sheet), not show raw conversion precision. The
        // underlying format is locale-dependent, so pin it for a deterministic result.
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("232.74", Fiat.parseFiat("USD", "232.7405").toDisplayNoCode())
            assertEquals("0.93", Fiat.parseFiat("USD", "0.93").toDisplayNoCode())
            assertEquals("5.00", Fiat.parseFiat("USD", "5").toDisplayNoCode())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `fiat plain two-decimal string rounds and keeps a dot separator`() {
        // MO-1111 follow-up: the editable keypad field also showed raw conversion
        // precision when switching into fiat mode. Unlike toDisplayNoCode, this format
        // never calls withLocale(), so it must stay "." regardless of default locale.
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("232.74", Fiat.parseFiat("USD", "232.7405").toPlainStringTwoDecimals())
            assertEquals("0.93", Fiat.parseFiat("USD", "0.93").toPlainStringTwoDecimals())
            assertEquals("5.00", Fiat.parseFiat("USD", "5").toPlainStringTwoDecimals())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `fiat plain two-decimal string rounds down, never up`() {
        // This text is re-parsed back into the spendable amount (onMaxClick feeds it the
        // full balance), so rounding up would make the re-parsed amount exceed the balance
        // it came from and falsely trip "insufficient funds" right after tapping Max.
        assertEquals("30.33", Fiat.parseFiat("USD", "30.335").toPlainStringTwoDecimals())
        assertEquals("30.33", Fiat.parseFiat("USD", "30.339").toPlainStringTwoDecimals())
    }
}
