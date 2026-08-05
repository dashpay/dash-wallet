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
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.creditsToDash
import de.schildbach.wallet.service.platform.sdk.dashToCredits
import de.schildbach.wallet.service.platform.sdk.shieldedInviteDenominationCredits
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the create-invitation shielded-funding decision state
 * ([InviteShieldedFundingUIState]). Constructs the state directly with
 * explicit [Dash] values — deliberately WITHOUT loading `Constants` (host-JVM
 * class-init is heavy; the existing SDK shielded tests follow the same rule)
 * — and never touches the `Constants`-referencing `canShieldMinimum` getter.
 */
class InviteShieldedFundingUIStateTest {

    // 1 DASH = 1e8 duffs.
    private val nonContestedFee = Dash(3_000_000L) // 0.03 DASH
    private val contestedFee = Dash(25_000_000L) // 0.25 DASH

    @Test
    fun `prompt is NONE while shielded features are off`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = false,
            resolved = true,
            nonContestedFee = nonContestedFee,
            contestedFee = contestedFee
        )
        assertEquals(InviteShieldedFundingPrompt.NONE, state.prompt)
    }

    @Test
    fun `prompt is MAKE_INVITE_PRIVATE when shielded features are on`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            nonContestedFee = nonContestedFee,
            contestedFee = contestedFee
        )
        assertEquals(InviteShieldedFundingPrompt.MAKE_INVITE_PRIVATE, state.prompt)
    }

    @Test
    fun `shielded costs are the fund-minimum amounts to shield, not the bare exit denomination`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            nonContestedFee = nonContestedFee,
            contestedFee = contestedFee
        )
        // The sheet asks for the amount to SHIELD: 0.035 / 0.26 — round
        // user-facing guidance above denomination + Constants.SHIELDED_FEE_MARGIN
        // (product decision 2026-08-05), not the bare 0.03/0.25.
        assertEquals(Dash(3_500_000L), state.nonContestedShieldedCost)
        assertEquals(Dash(26_000_000L), state.contestedShieldedCost)
    }

    @Test
    fun `withdrawn amounts match the minted denominations`() {
        // MINT PARITY PIN (the v13 UI/mint split, T-2): the sheet's Private
        // withdrawn figures must equal what the mint actually funds
        // (shieldedInviteDenominationCredits for the tier's fee). A future
        // platform-version change to the exit-denomination set fails here
        // instead of silently splitting the UI from the mint again.
        val state = InviteShieldedFundingUIState()
        assertEquals(
            creditsToDash(
                shieldedInviteDenominationCredits(dashToCredits(Dash(Constants.DASH_PAY_FEE.value)))!!
            ),
            state.nonContestedPrivateWithdrawn
        )
        assertEquals(
            creditsToDash(
                shieldedInviteDenominationCredits(dashToCredits(Dash(Constants.DASH_PAY_FEE_CONTESTED.value)))!!
            ),
            state.contestedPrivateWithdrawn
        )
    }

    @Test
    fun `fund-minimums never fall below the minted denominations plus the shield-fee margin`() {
        // The shield-IN guidance is a ROUND number (0.035 / 0.26, product
        // decision 2026-08-05) but must never drop below the enforced
        // minimum: denomination + the 0.003 DASH shielded-fee margin
        // (Constants.SHIELDED_FEE_MARGIN — the Shield entry's consensus fee,
        // ~0.00213, is deducted from the locked amount, so the bare
        // denomination lands short; the pre-2026-08 0.05 pad was a guess).
        val padDuffs = 300_000L // 0.003 DASH
        val state = InviteShieldedFundingUIState()
        assertTrue(
            "non-contested guidance below denomination + margin",
            state.nonContestedShieldedCost.duffs >= state.nonContestedPrivateWithdrawn.duffs + padDuffs
        )
        assertTrue(
            "contested guidance below denomination + margin",
            state.contestedShieldedCost.duffs >= state.contestedPrivateWithdrawn.duffs + padDuffs
        )
    }

    @Test
    fun `shielded costs are fixed fund-minimums independent of the L1 fee input`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            nonContestedFee = Dash.ZERO,
            contestedFee = Dash.ZERO
        )
        assertEquals(Dash(3_500_000L), state.nonContestedShieldedCost)
        assertEquals(Dash(26_000_000L), state.contestedShieldedCost)
    }

    // ── canCreatePrivateInvite gates on the withdrawn cost + Type-16
    //    transfer-fee margin (0.033), not the bare denomination and not the
    //    shield-IN fund-minimum ────────────────────────────────────────────

    @Test
    fun `canCreatePrivateInvite true once the pool holds denomination plus mint fee margin`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(3_300_000L) // exactly 0.033 — denomination + margin
        )
        assertTrue(state.canCreatePrivateInvite)
    }

    @Test
    fun `canCreatePrivateInvite true for a pool above the gate but below the shield-IN contested minimum`() {
        // A pool holding 0.05 (>= 0.033 gate) can fund a non-contested private
        // invite — gating entry on any L1 shield-IN figure wrongly hid it (the
        // Shield fee is irrelevant once funds are already shielded).
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(5_000_000L) // 0.05 DASH
        )
        assertTrue(state.canCreatePrivateInvite)
    }

    @Test
    fun `canCreatePrivateInvite false at exactly the bare denomination`() {
        // FIX-1 REGRESSION PIN: exactly 0.03 used to pass and then fail
        // opaquely at the FFI — the Type-16 mint fee is charged on top of the
        // funded notes, so the bare denomination cannot mint.
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(3_000_000L) // exactly 0.03 DASH
        )
        assertFalse(state.canCreatePrivateInvite)
    }

    @Test
    fun `canCreatePrivateInvite false just below the gate`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(3_299_999L) // just under 0.033 DASH
        )
        assertFalse(state.canCreatePrivateInvite)
    }

    @Test
    fun `canCreatePrivateInvite false while the pool is still syncing`() {
        // A mid-sync Dash.ZERO-or-stale balance is never trusted, even at/above
        // the withdrawn cost.
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.NOT_READY,
            shieldedBalance = Dash(50_000_000L) // 0.5 DASH but NOT_READY
        )
        assertFalse(state.canCreatePrivateInvite)
    }

    // ── Decision-sheet option set (pure, no Constants) ────────────────────

    @Test
    fun options_poolCanFund_offersCreatePrivateInsteadOfShieldFirst() {
        // Pool can fund AND wallet could shield — the private-invite path wins;
        // shield-first is not shown.
        assertEquals(
            listOf(
                InviteShieldedOption.CREATE_PRIVATE_INVITE,
                InviteShieldedOption.CONTINUE_WITHOUT_PRIVACY
            ),
            inviteShieldedOptions(canCreatePrivateInvite = true, canShieldMinimum = true)
        )
    }

    @Test
    fun options_poolCannotFundButWalletCanShield_offersShieldFirst() {
        assertEquals(
            listOf(
                InviteShieldedOption.SHIELD_FIRST,
                InviteShieldedOption.CONTINUE_WITHOUT_PRIVACY
            ),
            inviteShieldedOptions(canCreatePrivateInvite = false, canShieldMinimum = true)
        )
    }

    @Test
    fun options_neitherAvailable_onlyContinueWithoutPrivacy() {
        assertEquals(
            listOf(InviteShieldedOption.CONTINUE_WITHOUT_PRIVACY),
            inviteShieldedOptions(canCreatePrivateInvite = false, canShieldMinimum = false)
        )
    }

    @Test
    fun options_continueWithoutPrivacyIsAlwaysPresentAndLast() {
        for (canCreate in listOf(true, false)) {
            for (canShield in listOf(true, false)) {
                val options = inviteShieldedOptions(canCreate, canShield)
                assertEquals(
                    InviteShieldedOption.CONTINUE_WITHOUT_PRIVACY,
                    options.last()
                )
            }
        }
    }
}
