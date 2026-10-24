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
package de.schildbach.wallet.ui.username.request

import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import de.schildbach.wallet.ui.username.UsernameType
import org.junit.Assert.assertEquals
import org.junit.Test

/** [reuseUsernameType]: which name the REUSE_TRANSACTION entry of the request screen asks for. */
class ReuseUsernameTypeTest {

    private fun identity(state: IdentityCreationState, error: String?, secondary: String?) = BlockchainIdentityData(
        state,
        error,
        "alice3",
        secondary,
        "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
        false
    )

    @Test
    fun `a failed name-only retry replaces the primary even when a secondary is registered`() {
        val failedRetry = identity(
            IdentityCreationState.USERNAME_REGISTERING,
            "${SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR}: NOT_REGISTERED",
            "alice2instant"
        )

        assertEquals(UsernameType.Primary, reuseUsernameType(failedRetry, UsernameType.Secondary))
    }

    @Test
    fun `a failed name-only retry replaces the primary whatever the recorded state`() {
        val failedRetry = identity(
            IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
            "${SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR}: AMBIGUOUS",
            "alice2instant"
        )

        assertEquals(UsernameType.Primary, reuseUsernameType(failedRetry, UsernameType.Secondary))
    }

    @Test
    fun `other errors keep the older heuristic`() {
        val error = "username registration did not complete (retryable): domain document rejected"

        assertEquals(
            UsernameType.Secondary,
            reuseUsernameType(identity(IdentityCreationState.USERNAME_REGISTERING, error, "alice2instant"), UsernameType.Primary)
        )
        assertEquals(
            UsernameType.Primary,
            reuseUsernameType(identity(IdentityCreationState.USERNAME_REGISTERING, error, null), UsernameType.Secondary)
        )
        assertEquals(
            UsernameType.Secondary,
            reuseUsernameType(identity(IdentityCreationState.VOTING, error, null), UsernameType.Secondary)
        )
    }

    @Test
    fun `no record or no error uses the navigation argument`() {
        assertEquals(UsernameType.Secondary, reuseUsernameType(null, UsernameType.Secondary))
        assertEquals(
            UsernameType.Secondary,
            reuseUsernameType(identity(IdentityCreationState.USERNAME_REGISTERING, null, "alice2instant"), UsernameType.Secondary)
        )
    }
}
