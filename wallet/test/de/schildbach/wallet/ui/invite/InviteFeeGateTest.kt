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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the invitation-fee dialog's enable/continue gate
 * ([inviteFeeGate]) — host-JVM, no Android/native deps, following the
 * `inviteShieldedOptions` / `usernameSubmitButtonState` helper-test pattern.
 * Pins the Fix-F regression: a private (shielded) invite disabled the
 * contested tile because it read the (now-low) L1 balance instead of the
 * shielded pool.
 */
class InviteFeeGateTest {

    // ---- L1 invite (args.shielded == false): unchanged behavior. ----

    @Test
    fun `L1 with enough for contested enables both`() {
        val (contested, cont) = inviteFeeGate(
            shielded = false,
            l1Balance = Constants.DASH_PAY_FEE_CONTESTED, // 0.25
            shieldedReady = false,
            shieldedBalance = Coin.ZERO,
            contestedSelected = true
        )
        assertTrue(contested)
        assertTrue(cont)
    }

    @Test
    fun `L1 with only the non-contested fee disables contested but allows non-contested continue`() {
        // 0.03 <= balance < 0.25
        val balance = Constants.DASH_PAY_FEE // 0.03
        val contestedSelected = inviteFeeGate(
            shielded = false,
            l1Balance = balance,
            shieldedReady = false,
            shieldedBalance = Coin.ZERO,
            contestedSelected = true
        )
        assertFalse("contested not affordable", contestedSelected.first)
        assertFalse("continue disabled while contested is selected", contestedSelected.second)

        val nonContestedSelected = inviteFeeGate(
            shielded = false,
            l1Balance = balance,
            shieldedReady = false,
            shieldedBalance = Coin.ZERO,
            contestedSelected = false
        )
        assertFalse(nonContestedSelected.first)
        assertTrue("continue enabled for the non-contested fee", nonContestedSelected.second)
    }

    // ---- Private invite (args.shielded == true): gate on the pool. ----

    @Test
    fun `shielded not-ready keeps contested disabled even with a stale-looking balance`() {
        // A mid-sync balance can read high; it is NOT evidence until READY.
        val (contested, cont) = inviteFeeGate(
            shielded = true,
            l1Balance = Coin.ZERO,
            shieldedReady = false,
            shieldedBalance = Coin.parseCoin("1.0"),
            contestedSelected = true
        )
        assertFalse("contested disabled until the pool is READY", contested)
        assertFalse("continue disabled until the pool is READY", cont)
    }

    @Test
    fun `shielded ready at or above the contested minimum enables both`() {
        val (contested, cont) = inviteFeeGate(
            shielded = true,
            l1Balance = Coin.ZERO,
            shieldedReady = true,
            shieldedBalance = Constants.SHIELDED_USERNAME_FUND_MIN_CONTESTED, // 0.35
            contestedSelected = true
        )
        assertTrue(contested)
        assertTrue(cont)
    }

    @Test
    fun `shielded ready between the two minimums allows only non-contested`() {
        val balance = Coin.parseCoin("0.20") // 0.15 <= balance < 0.35
        val contestedSelected = inviteFeeGate(
            shielded = true,
            l1Balance = Coin.ZERO,
            shieldedReady = true,
            shieldedBalance = balance,
            contestedSelected = true
        )
        assertFalse("contested needs 0.35", contestedSelected.first)
        assertFalse("continue disabled while contested is selected", contestedSelected.second)

        val nonContestedSelected = inviteFeeGate(
            shielded = true,
            l1Balance = Coin.ZERO,
            shieldedReady = true,
            shieldedBalance = balance,
            contestedSelected = false
        )
        assertFalse(nonContestedSelected.first)
        assertTrue("non-contested needs only 0.15", nonContestedSelected.second)
    }
}
