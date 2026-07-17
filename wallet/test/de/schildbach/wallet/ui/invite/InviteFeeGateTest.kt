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
package de.schildbach.wallet.ui.invite

import de.schildbach.wallet.Constants
import org.bitcoinj.core.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the invitation-fee dialog's "Confirm and pay" gate
 * ([inviteFeeGate]) and requirement resolver ([inviteFeeRequirement]) —
 * host-JVM, no Android/native deps, following the `inviteShieldedOptions` /
 * `usernameSubmitButtonState` helper-test pattern.
 *
 * Fix G2: both username-kind tiles stay selectable regardless of balance, so
 * the gate no longer emits a tile-enabled flag — it is a single button gate
 * for the CURRENTLY selected kind. Fix F is still pinned: a private invite
 * gates on the shielded pool, not the (now-low) L1 balance, and a mid-sync
 * pool balance is never trusted.
 */
class InviteFeeGateTest {

    // ---- requirement per source + selected kind ----

    @Test
    fun `requirement is the L1 fee for a standard invite`() {
        assertEquals(Constants.DASH_PAY_FEE, inviteFeeRequirement(shielded = false, contestedSelected = false))
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED, inviteFeeRequirement(shielded = false, contestedSelected = true))
    }

    @Test
    fun `requirement is the withdrawn Type-20 denomination for a private invite`() {
        // Matches the amount shown on the tiles / confirm screen (0.1 / 0.3),
        // not the padded pool fund-minimum.
        assertEquals(Coin.parseCoin("0.1"), inviteFeeRequirement(shielded = true, contestedSelected = false))
        assertEquals(Coin.parseCoin("0.3"), inviteFeeRequirement(shielded = true, contestedSelected = true))
    }

    // ---- L1 invite (args.shielded == false) ----

    @Test
    fun `L1 continue enabled once the wallet holds the selected fee`() {
        assertTrue(gate(shielded = false, l1 = Constants.DASH_PAY_FEE_CONTESTED, contestedSelected = true))
        assertTrue(gate(shielded = false, l1 = Constants.DASH_PAY_FEE, contestedSelected = false))
    }

    @Test
    fun `L1 with only the non-contested fee disables continue for the contested selection`() {
        val balance = Constants.DASH_PAY_FEE // 0.03 <= balance < 0.25
        assertFalse("contested selection unaffordable", gate(shielded = false, l1 = balance, contestedSelected = true))
        assertTrue("non-contested selection affordable", gate(shielded = false, l1 = balance, contestedSelected = false))
    }

    // ---- Private invite (args.shielded == true): gate on the pool ----

    @Test
    fun `shielded not-ready disables continue even with a stale-looking balance`() {
        // A mid-sync balance can read high; it is NOT evidence until READY.
        assertFalse(
            inviteFeeGate(
                shielded = true,
                l1Balance = Coin.ZERO,
                shieldedReady = false,
                shieldedBalance = Coin.parseCoin("1.0"),
                contestedSelected = true
            )
        )
    }

    @Test
    fun `shielded ready at or above the contested minimum enables continue`() {
        assertTrue(
            inviteFeeGate(
                shielded = true,
                l1Balance = Coin.ZERO,
                shieldedReady = true,
                shieldedBalance = Coin.parseCoin("0.3"), // the withdrawn contested amount
                contestedSelected = true
            )
        )
    }

    @Test
    fun `shielded ready between the two minimums allows only the non-contested selection`() {
        val balance = Coin.parseCoin("0.20") // 0.1 <= balance < 0.3
        assertFalse(
            "contested needs 0.3",
            inviteFeeGate(true, Coin.ZERO, shieldedReady = true, shieldedBalance = balance, contestedSelected = true)
        )
        assertTrue(
            "non-contested needs only 0.1",
            inviteFeeGate(true, Coin.ZERO, shieldedReady = true, shieldedBalance = balance, contestedSelected = false)
        )
    }

    private fun gate(shielded: Boolean, l1: Coin, contestedSelected: Boolean): Boolean =
        inviteFeeGate(
            shielded = shielded,
            l1Balance = l1,
            shieldedReady = false,
            shieldedBalance = Coin.ZERO,
            contestedSelected = contestedSelected
        )
}
