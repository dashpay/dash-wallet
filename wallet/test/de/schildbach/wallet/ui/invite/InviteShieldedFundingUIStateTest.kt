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
import org.junit.Assert.assertNull
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
    fun `shielded costs come from the shared denomination source, not the bare fee`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            nonContestedFee = nonContestedFee,
            contestedFee = contestedFee
        )
        // 0.03 fee → smallest covering Type-20 denomination 0.1 DASH.
        assertEquals(Dash(10_000_000L), state.nonContestedShieldedCost)
        // 0.25 fee → 0.3 DASH (0.1 cannot cover the contested prefunded vote).
        assertEquals(Dash(30_000_000L), state.contestedShieldedCost)
    }

    @Test
    fun `shielded cost is null when no denomination covers the fee`() {
        val state = InviteShieldedFundingUIState(
            shieldedEnabled = true,
            resolved = true,
            nonContestedFee = Dash.ZERO,
            contestedFee = Dash(200_000_000L) // 2.0 DASH — above the largest denomination
        )
        assertNull(state.nonContestedShieldedCost)
        assertNull(state.contestedShieldedCost)
    }
}
