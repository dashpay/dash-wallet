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
        // The sheet asks for the amount to SHIELD: 0.08 / 0.30 (the v13
        // 0.03/0.25 exit denomination padded 0.05 for the shielded-spend
        // fee), not the bare 0.03/0.25.
        assertEquals(Dash(8_000_000L), state.nonContestedShieldedCost)
        assertEquals(Dash(30_000_000L), state.contestedShieldedCost)
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
    fun `fund-minimums are the minted denominations plus the shield-fee pad`() {
        // The padded shield-IN guidance must track the SAME minted
        // denominations: denomination + 0.05 DASH pad (the pad the pre-v13
        // 0.15/0.35 figures encoded over 0.1/0.3).
        val padDuffs = 5_000_000L // 0.05 DASH
        val state = InviteShieldedFundingUIState()
        assertEquals(
            Dash(state.nonContestedPrivateWithdrawn.duffs + padDuffs),
            state.nonContestedShieldedCost
        )
        assertEquals(
            Dash(state.contestedPrivateWithdrawn.duffs + padDuffs),
            state.contestedShieldedCost
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
        assertEquals(Dash(8_000_000L), state.nonContestedShieldedCost)
        assertEquals(Dash(30_000_000L), state.contestedShieldedCost)
    }

    // ── canCreatePrivateInvite gates on the WITHDRAWN cost (0.03), not the
    //    padded shield-IN fund-minimum (0.08) ─────────────────────────────────

    @Test
    fun `canCreatePrivateInvite true once the pool holds the withdrawn cost`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(3_000_000L) // exactly 0.03 DASH — the withdrawn denomination
        )
        assertTrue(state.canCreatePrivateInvite)
    }

    @Test
    fun `canCreatePrivateInvite true for a pool between the withdrawn cost and the padded gate`() {
        // A pool holding 0.05 (>= 0.03 withdrawn, < 0.08 shield-IN minimum) can
        // fund a non-contested private invite — gating on the padded minimum
        // wrongly hid it (the shield-IN pad is irrelevant once funds are
        // already shielded).
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(5_000_000L) // 0.05 DASH
        )
        assertTrue(state.canCreatePrivateInvite)
    }

    @Test
    fun `canCreatePrivateInvite false below the withdrawn cost`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(2_999_999L) // just under 0.03 DASH
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
