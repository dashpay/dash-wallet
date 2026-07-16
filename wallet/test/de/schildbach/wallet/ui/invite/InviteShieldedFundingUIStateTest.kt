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

import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
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
        // The sheet asks for the amount to SHIELD: 0.15 / 0.35 (the 0.1/0.3
        // exit denomination padded for the shielded-spend fee), not 0.1/0.3.
        assertEquals(Dash(15_000_000L), state.nonContestedShieldedCost)
        assertEquals(Dash(35_000_000L), state.contestedShieldedCost)
    }

    @Test
    fun `shielded costs are fixed fund-minimums independent of the L1 fee input`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            nonContestedFee = Dash.ZERO,
            contestedFee = Dash.ZERO
        )
        assertEquals(Dash(15_000_000L), state.nonContestedShieldedCost)
        assertEquals(Dash(35_000_000L), state.contestedShieldedCost)
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
