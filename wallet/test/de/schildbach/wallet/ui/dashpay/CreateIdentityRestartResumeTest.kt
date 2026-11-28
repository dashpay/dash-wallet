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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a START_STICKY null-intent restart of CreateIdentityService resumes.
 * NONE means the service has no work and must stop itself instead of idling
 * in the foreground with a wake lock.
 */
class CreateIdentityRestartResumeTest {
    private fun record(
        creationState: IdentityCreationState,
        usingInvite: Boolean,
        restoring: Boolean = false
    ) = BlockchainIdentityBaseData(
        creationState = creationState,
        creationStateErrorMessage = null,
        username = "someone",
        usernameSecondary = null,
        userId = "Dpm",
        restoring = restoring,
        usingInvite = usingInvite
    )

    @Test
    fun `an unfinished invite claim resumes on the invite path`() {
        assertEquals(
            RestartResumeAction.INVITE,
            restartResumeAction(record(IdentityCreationState.IDENTITY_REGISTERING, usingInvite = true))
        )
    }

    @Test
    fun `an invite claim at VOTING or later has nothing to resume`() {
        assertEquals(
            RestartResumeAction.NONE,
            restartResumeAction(record(IdentityCreationState.VOTING, usingInvite = true))
        )
        assertEquals(
            RestartResumeAction.NONE,
            restartResumeAction(record(IdentityCreationState.DONE_AND_DISMISS, usingInvite = true))
        )
    }

    @Test
    fun `an unfinished non-invite creation resumes on the identity path`() {
        assertEquals(
            RestartResumeAction.IDENTITY,
            restartResumeAction(record(IdentityCreationState.IDENTITY_REGISTERING, usingInvite = false))
        )
    }

    @Test
    fun `no record, a finished one, or a restore has nothing to resume`() {
        assertEquals(RestartResumeAction.NONE, restartResumeAction(null))
        assertEquals(
            RestartResumeAction.NONE,
            restartResumeAction(record(IdentityCreationState.DONE, usingInvite = false))
        )
        assertEquals(
            RestartResumeAction.NONE,
            restartResumeAction(
                record(IdentityCreationState.IDENTITY_REGISTERING, usingInvite = true, restoring = true)
            )
        )
    }
}
