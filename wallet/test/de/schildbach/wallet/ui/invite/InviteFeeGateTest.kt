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
import de.schildbach.wallet.service.platform.sdk.SHIELDED_INVITE_FEE_MARGIN_CREDITS
import de.schildbach.wallet.service.platform.sdk.creditsToDash
import de.schildbach.wallet.service.platform.sdk.dashToCredits
import de.schildbach.wallet.service.platform.sdk.shieldedInviteDenominationCredits
import org.bitcoinj.core.Coin
import org.dash.wallet.common.money.Dash
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
    fun `requirement is denomination plus transfer-fee margin for a private invite`() {
        // The mint's Type-16 fee is carved from the pool ON TOP of the funded
        // notes (unlike the Type-20 exit, whose fee is metered out of the
        // denomination), so the HOLD requirement — and the "you need at least
        // X" insufficiency copy — is denomination + margin. The tiles/confirm
        // screen still DISPLAY the bare 0.03/0.25 denomination.
        assertEquals(Coin.parseCoin("0.033"), inviteFeeRequirement(shielded = true, contestedSelected = false))
        assertEquals(Coin.parseCoin("0.253"), inviteFeeRequirement(shielded = true, contestedSelected = true))
    }

    @Test
    fun `shielded requirements match the minted denominations plus the fee margin`() {
        // MINT PARITY PIN (the v13 UI/mint split, T-2): the UI's shielded
        // requirement constants must equal what the mint actually funds —
        // shieldedInviteDenominationCredits for the tier's fee — plus the
        // Type-16 transfer-fee margin the mint carves from the pool on top.
        // If a platform version revises the exit-denomination set again, this
        // fails the build instead of letting the UI advertise/gate amounts the
        // mint no longer produces (the 0.1/0.3-vs-0.03/0.25 regression).
        for (contested in listOf(false, true)) {
            val fee = if (contested) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
            val mintedCredits = shieldedInviteDenominationCredits(dashToCredits(Dash(fee.value)))
            val required = Coin.valueOf(
                creditsToDash(mintedCredits!! + SHIELDED_INVITE_FEE_MARGIN_CREDITS).duffs
            )
            assertEquals(
                "UI requirement (contested=$contested) diverged from the minted denomination + margin",
                required,
                inviteFeeRequirement(shielded = true, contestedSelected = contested)
            )
        }
    }

    @Test
    fun `credits-space fee margin equals the Coin-space shielded fee margin`() {
        // SHIELDED_INVITE_FEE_MARGIN_CREDITS (the SDK-layer constant the
        // service preflight uses) and Constants.SHIELDED_FEE_MARGIN (the
        // Coin-space pad the shield-first fund-minimums use) must be the same
        // value — both derive from the same consensus fee formula.
        assertEquals(
            Constants.SHIELDED_FEE_MARGIN,
            Coin.valueOf(creditsToDash(SHIELDED_INVITE_FEE_MARGIN_CREDITS).duffs)
        )
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
    fun `shielded ready at or above the contested requirement enables continue`() {
        assertTrue(
            inviteFeeGate(
                shielded = true,
                l1Balance = Coin.ZERO,
                shieldedReady = true,
                shieldedBalance = Coin.parseCoin("0.253"), // 0.25 denomination + 0.003 margin
                contestedSelected = true
            )
        )
    }

    @Test
    fun `a pool holding exactly the bare denomination does NOT enable continue`() {
        // THE FIX-1 REGRESSION PIN: exactly the denomination used to pass the
        // gate and then fail opaquely at the FFI, because the Type-16 mint fee
        // is charged on top of the funded notes.
        assertFalse(
            "exactly 0.25 cannot pay the contested mint's transfer fee",
            inviteFeeGate(
                shielded = true,
                l1Balance = Coin.ZERO,
                shieldedReady = true,
                shieldedBalance = Coin.parseCoin("0.25"),
                contestedSelected = true
            )
        )
        assertFalse(
            "exactly 0.03 cannot pay the non-contested mint's transfer fee",
            inviteFeeGate(
                shielded = true,
                l1Balance = Coin.ZERO,
                shieldedReady = true,
                shieldedBalance = Coin.parseCoin("0.03"),
                contestedSelected = false
            )
        )
    }

    @Test
    fun `shielded ready between the two requirements allows only the non-contested selection`() {
        val balance = Coin.parseCoin("0.20") // 0.033 <= balance < 0.253
        assertFalse(
            "contested needs 0.253",
            inviteFeeGate(true, Coin.ZERO, shieldedReady = true, shieldedBalance = balance, contestedSelected = true)
        )
        assertTrue(
            "non-contested needs only 0.033",
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
