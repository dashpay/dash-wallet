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
    private val balance = Coin.valueOf(150_000_000L)

    private fun decide(
        isTimeToRemind: Boolean = true,
        walletExists: Boolean = true,
        lastTotalBalance: Coin? = balance,
        hideBalance: Boolean = false
    ) = InactivityReminder.decide(isTimeToRemind, walletExists, lastTotalBalance, hideBalance)

    @Test
    fun `shows the last known balance when autohide is off`() {
        assertEquals(InactivityReminder.Content.WithAmount(balance), decide())
    }

    @Test
    fun `leaves the amount out when autohide is on`() {
        assertEquals(InactivityReminder.Content.AmountHidden, decide(hideBalance = true))
    }

    @Test
    fun `no reminder when the last known balance is unknown`() {
        assertNull(decide(lastTotalBalance = null))
        assertNull(decide(lastTotalBalance = null, hideBalance = true))
    }

    @Test
    fun `no reminder when the last known balance is zero`() {
        assertNull(decide(lastTotalBalance = Coin.ZERO))
        assertNull(decide(lastTotalBalance = Coin.ZERO, hideBalance = true))
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
