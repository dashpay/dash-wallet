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

package de.schildbach.wallet.ui.username.request

import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Test

class UsernameDisclaimerBalanceTest {
    @Test
    fun feeFromShielded_quotesTheShieldedBalance() {
        assertEquals(
            Dash(30_000_000L),
            usernameDisclaimerBalance(canPayFeeFromShielded = true, shieldedBalance = Dash(30_000_000L), walletBalanceDuffs = 0L)
        )
    }

    @Test
    fun feeFromL1_quotesTheL1Balance() {
        assertEquals(
            Dash(1_234L),
            usernameDisclaimerBalance(canPayFeeFromShielded = false, shieldedBalance = Dash(30_000_000L), walletBalanceDuffs = 1_234L)
        )
    }
}
