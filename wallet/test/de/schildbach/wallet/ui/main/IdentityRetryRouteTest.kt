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

package de.schildbach.wallet.ui.main

import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import org.junit.Assert.assertEquals
import org.junit.Test

/** The home tile's "Try again" after a name-only retry stopped with an error. */
class IdentityRetryRouteTest {
    private val nameOnlyError = SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR + ": domain document rejected"

    @Test
    fun `a name-only retry error goes back to the name-only retry, never to CreateIdentityService`() {
        assertEquals(IdentityRetryRoute.NAME_ONLY, identityRetryRoute(nameOnlyError, restoring = false, usingInvite = false))
    }

    @Test
    fun `a name-only failure because the name is taken asks for a new name instead`() {
        listOf(
            "Document transitions with duplicate unique properties",
            "DuplicateUniqueIndexError",
            "missing domain document for alice3"
        ).forEach { cause ->
            assertEquals(
                cause,
                IdentityRetryRoute.NEW_NAME,
                identityRetryRoute(SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR + ": " + cause, restoring = false, usingInvite = false)
            )
        }
    }

    @Test
    fun `other errors keep their routes`() {
        assertEquals(IdentityRetryRoute.RESTORE, identityRetryRoute("x", restoring = true, usingInvite = false))
        assertEquals(IdentityRetryRoute.LEGACY, identityRetryRoute("x", restoring = false, usingInvite = false))
        assertEquals(IdentityRetryRoute.INVITE, identityRetryRoute("x", restoring = false, usingInvite = true))
    }
}
