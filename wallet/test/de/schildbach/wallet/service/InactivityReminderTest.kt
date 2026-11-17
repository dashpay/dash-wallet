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
package de.schildbach.wallet.service

import org.bitcoinj.core.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InactivityReminderTest {
    private val transparent = Coin.valueOf(150_000_000L)
    private val shielded = Coin.valueOf(20_000_000L)

    private fun decide(
        isTimeToRemind: Boolean = true,
        walletExists: Boolean = true,
        transparentBalance: Coin? = transparent,
        shieldedBalance: Coin? = Coin.ZERO,
        hideBalance: Boolean = false
    ) = InactivityReminder.decide(isTimeToRemind, walletExists, transparentBalance, shieldedBalance, hideBalance)

    @Test
    fun `shows the last known balance when autohide is off`() {
        assertEquals(InactivityReminder.Content.WithAmount(transparent), decide())
    }

    @Test
    fun `adds the shielded balance like the home header`() {
        assertEquals(
            InactivityReminder.Content.WithAmount(transparent.add(shielded)),
            decide(shieldedBalance = shielded)
        )
    }

    @Test
    fun `reminds a wallet whose funds are all shielded`() {
        assertEquals(
            InactivityReminder.Content.WithAmount(shielded),
            decide(transparentBalance = Coin.ZERO, shieldedBalance = shielded)
        )
    }

    @Test
    fun `leaves the amount out when autohide is on`() {
        assertEquals(InactivityReminder.Content.AmountHidden, decide(hideBalance = true))
    }

    @Test
    fun `an unknown part never shows a smaller amount`() {
        assertEquals(InactivityReminder.Content.AmountHidden, decide(shieldedBalance = null))
        assertEquals(
            InactivityReminder.Content.AmountHidden,
            decide(transparentBalance = null, shieldedBalance = shielded)
        )
    }

    @Test
    fun `no reminder when no part is known to be funded`() {
        assertNull(decide(transparentBalance = null, shieldedBalance = null))
        assertNull(decide(transparentBalance = Coin.ZERO, shieldedBalance = null))
        assertNull(decide(transparentBalance = null, shieldedBalance = Coin.ZERO, hideBalance = true))
    }

    @Test
    fun `no reminder when the last known balance is zero`() {
        assertNull(decide(transparentBalance = Coin.ZERO))
        assertNull(decide(transparentBalance = Coin.ZERO, hideBalance = true))
    }

    @Test
    fun `no reminder when it is not time yet`() {
        assertNull(decide(isTimeToRemind = false))
    }

    @Test
    fun `no reminder without a wallet`() {
        assertNull(decide(walletExists = false))
    }
}
