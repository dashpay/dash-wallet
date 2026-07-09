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
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
