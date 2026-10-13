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

package de.schildbach.wallet.ui

import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.IdentityCreationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when a newly opened invite is answered with "DashPay is currently
 * processing an invite" (MO-974): only while an identity is being created
 * from an invite — never for a wallet that already has an identity.
 */
class InviteClaimInProgressTest {

    private fun record(
        creationState: IdentityCreationState,
        usingInvite: Boolean,
        creationStateErrorMessage: String? = null
    ) = BlockchainIdentityBaseData(
        creationState = creationState,
        creationStateErrorMessage = creationStateErrorMessage,
        username = "someone",
        usernameSecondary = null,
        userId = "Dpm",
        restoring = false,
        usingInvite = usingInvite
    )

    @Test
    fun `no identity record means no invite is being processed`() {
        assertFalse(inviteClaimInProgress(null))
        assertFalse(inviteClaimInProgress(record(IdentityCreationState.NONE, usingInvite = true)))
    }

    @Test
    fun `an invite-based creation in progress blocks a new invite`() {
        assertTrue(inviteClaimInProgress(record(IdentityCreationState.IDENTITY_REGISTERING, usingInvite = true)))
        assertTrue(
            inviteClaimInProgress(
                record(IdentityCreationState.IDENTITY_REGISTERING, usingInvite = true, creationStateErrorMessage = "x")
            )
        )
    }

    @Test
    fun `a wallet that already has an identity is not processing an invite`() {
        assertFalse(inviteClaimInProgress(record(IdentityCreationState.DONE, usingInvite = true)))
        assertFalse(inviteClaimInProgress(record(IdentityCreationState.DONE_AND_DISMISS, usingInvite = false)))
        assertFalse(inviteClaimInProgress(record(IdentityCreationState.VOTING, usingInvite = true)))
    }

    @Test
    fun `a paid creation in progress is not an invite claim`() {
        assertFalse(inviteClaimInProgress(record(IdentityCreationState.IDENTITY_REGISTERING, usingInvite = false)))
    }
}
