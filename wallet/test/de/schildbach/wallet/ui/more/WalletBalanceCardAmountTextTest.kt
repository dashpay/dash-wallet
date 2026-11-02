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

package de.schildbach.wallet.ui.more

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.RoundingMode

/**
 * Display decision for the More-screen "Dash Wallet" balance card
 * ([walletBalanceCardAmountText]) — MO-1107: the card must honour Autohide
 * Balance the same way the Home header and the sibling Shielded card do
 * (see [ShieldedCardDisplayTest]), instead of always rendering a numeric
 * amount.
 */
class WalletBalanceCardAmountTextTest {

    private val format = MoneyFormat()
        .noCode()
        .minDecimals(3)
        .optionalDecimals()
        .roundingMode(RoundingMode.DOWN)

    private val placeholder = "••••••"

    @Test
    fun `hideBalance on shows the masked placeholder, not the formatted amount`() {
        assertEquals(
            placeholder,
            walletBalanceCardAmountText(
                Dash(150_000_000L), hideBalance = true, format = format, hiddenPlaceholder = placeholder
            )
        )
    }

    @Test
    fun `hideBalance on masks even a zero balance`() {
        // The bug this fixes: a zero balance ("0.000 Đ") still leaked a
        // literal amount on the More screen while Home showed only its eye
        // icon — zero must be masked exactly like any other amount.
        assertEquals(
            placeholder,
            walletBalanceCardAmountText(Dash.ZERO, hideBalance = true, format = format, hiddenPlaceholder = placeholder)
        )
    }

    @Test
    fun `hideBalance off shows the formatted amount`() {
        assertEquals(
            "1.500 Đ",
            walletBalanceCardAmountText(
                Dash(150_000_000L), hideBalance = false, format = format, hiddenPlaceholder = placeholder
            )
        )
    }

    @Test
    fun `hideBalance off shows the formatted zero amount unmasked`() {
        assertEquals(
            "0.000 Đ",
            walletBalanceCardAmountText(
                Dash.ZERO, hideBalance = false, format = format, hiddenPlaceholder = placeholder
            )
        )
    }
}
