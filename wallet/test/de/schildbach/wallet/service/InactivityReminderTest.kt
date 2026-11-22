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

import android.app.NotificationManager
import android.content.Context
import de.schildbach.wallet.Constants
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bitcoinj.core.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Notification text reaches notification listeners and the lock screen: shielded funds
     * are reminded about, but their amount stays out of it.
     */
    @Test
    fun `leaves the amount out when shielded funds are part of it`() {
        assertEquals(InactivityReminder.Content.AmountHidden, decide(shieldedBalance = shielded))
    }

    @Test
    fun `reminds a wallet whose funds are all shielded, without the amount`() {
        assertEquals(
            InactivityReminder.Content.AmountHidden,
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

    /** The first update: untagged balances are unknown, so a due reminder waits for them. */
    @Test
    fun `a due reminder whose balance is not known yet may be shown once it is stored`() {
        assertTrue(InactivityReminder.mayBecomeKnown(true, "wallet", null, Coin.ZERO))
        assertTrue(InactivityReminder.mayBecomeKnown(true, "wallet", Coin.ZERO, null))
    }

    @Test
    fun `nothing to wait for when the balance is known, not due, or the wallet unknown`() {
        assertFalse(InactivityReminder.mayBecomeKnown(true, "wallet", Coin.ZERO, Coin.ZERO))
        assertFalse(InactivityReminder.mayBecomeKnown(false, "wallet", null, null))
        assertFalse(InactivityReminder.mayBecomeKnown(true, null, null, null))
    }

    @Test
    fun `no reminder when it is not time yet`() {
        assertNull(decide(isTimeToRemind = false))
    }

    @Test
    fun `no reminder without a wallet`() {
        assertNull(decide(walletExists = false))
    }

    @Test
    fun `autohide removes the reminder before its setting is saved`() {
        val events = mutableListOf<String>()
        val notifications = mockk<NotificationManager> {
            every { cancel(Constants.NOTIFICATION_ID_INACTIVITY) } answers { events.add("cancel") }
        }
        val context = mockk<Context> {
            every { getSystemService(NotificationManager::class.java) } returns notifications
        }

        val result = InactivityReminder.cancelThen(context) { events.add("save"); "saved" }

        assertEquals("saved", result)
        assertEquals(listOf("cancel", "save"), events)
        verify(exactly = 1) { notifications.cancel(Constants.NOTIFICATION_ID_INACTIVITY) }
    }
}
