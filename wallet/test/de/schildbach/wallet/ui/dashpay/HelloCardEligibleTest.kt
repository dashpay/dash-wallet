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
package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.IdentityCreationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the home hello-card visibility gate
 * ([helloCardEligible]). Pins the dual-creation case: a contested PRIMARY in
 * VOTING with a registered INSTANT secondary must show the welcome card (the
 * instant name is usable immediately), while a contested-only creation in
 * voting keeps NO card and the restore path (DONE_AND_DISMISS) stays hidden.
 */
class HelloCardEligibleTest {

    private fun data(
        creationState: IdentityCreationState,
        username: String? = "alice",
        usernameSecondary: String? = null,
        errorMessage: String? = null,
        restoring: Boolean = false
    ) = BlockchainIdentityBaseData(
        creationState,
        errorMessage,
        username,
        usernameSecondary,
        "user-id",
        restoring
    )

    @Test
    fun `dual creation in voting with a registered instant username shows the card`() {
        assertTrue(
            helloCardEligible(
                data(IdentityCreationState.VOTING, usernameSecondary = "alice-2"),
                votingDualDismissed = false
            )
        )
    }

    @Test
    fun `dual voting card stays dismissed once dismissed`() {
        assertFalse(
            helloCardEligible(
                data(IdentityCreationState.VOTING, usernameSecondary = "alice-2"),
                votingDualDismissed = true
            )
        )
    }

    @Test
    fun `contested-only creation in voting shows no card`() {
        // Nothing is usable yet — the More screen's voting tile is the status surface.
        assertFalse(
            helloCardEligible(
                data(IdentityCreationState.VOTING, usernameSecondary = null),
                votingDualDismissed = false
            )
        )
    }

    @Test
    fun `non-contested single creation shows the card at DONE as today`() {
        assertTrue(
            helloCardEligible(
                data(IdentityCreationState.DONE),
                votingDualDismissed = false
            )
        )
    }

    @Test
    fun `creation in progress shows the card as today`() {
        assertTrue(
            helloCardEligible(
                data(IdentityCreationState.USERNAME_REGISTERING),
                votingDualDismissed = false
            )
        )
    }

    @Test
    fun `restore path lands on DONE_AND_DISMISS and shows no card`() {
        assertFalse(
            helloCardEligible(
                data(IdentityCreationState.DONE_AND_DISMISS),
                votingDualDismissed = false
            )
        )
    }

    @Test
    fun `DONE dismissal is unaffected by the voting-dual pref`() {
        // The DONE card's dismissal advances the state machine; the pref only
        // gates the voting-dual card.
        assertTrue(
            helloCardEligible(
                data(IdentityCreationState.DONE),
                votingDualDismissed = true
            )
        )
    }
}
