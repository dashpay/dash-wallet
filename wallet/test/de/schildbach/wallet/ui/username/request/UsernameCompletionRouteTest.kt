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

import de.schildbach.wallet.database.entity.IdentityCreationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the post-completion routing decision
 * ([usernameCompletionRoute]). A contested / in-voting username has no home
 * welcome tile, so it must land on the More screen's voting tile; everything
 * else returns to Home.
 */
class UsernameCompletionRouteTest {

    @Test
    fun `voting creation state routes to More`() {
        assertEquals(
            UsernameCompletionRoute.MORE,
            usernameCompletionRoute(IdentityCreationState.VOTING, usernameContestable = false)
        )
    }

    @Test
    fun `contestable flag routes to More even before the state reaches VOTING`() {
        // The L1 dialog-dismiss finish site may fire before the persisted
        // state advances to VOTING; the UI-known contestability still routes.
        assertEquals(
            UsernameCompletionRoute.MORE,
            usernameCompletionRoute(IdentityCreationState.USERNAME_REGISTERING, usernameContestable = true)
        )
    }

    @Test
    fun `non-contested completion routes to Home`() {
        assertEquals(
            UsernameCompletionRoute.HOME,
            usernameCompletionRoute(IdentityCreationState.DONE, usernameContestable = false)
        )
    }

    @Test
    fun `unknown state and non-contested routes to Home`() {
        assertEquals(
            UsernameCompletionRoute.HOME,
            usernameCompletionRoute(null, usernameContestable = false)
        )
    }

    @Test
    fun `dual creation routes to More via the primary contestability`() {
        // Dual (contested primary + instant secondary): the SECONDARY screen
        // finishes last, so its own contestable flag is false and the state
        // may not have flipped to VOTING yet — the primary's contestability
        // must still route MORE (observed live: dual completion went Home).
        assertEquals(
            UsernameCompletionRoute.MORE,
            usernameCompletionRoute(
                IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
                usernameContestable = false,
                primaryUsernameContestable = true
            )
        )
    }

    @Test
    fun `dual creation with the state already at VOTING routes to More`() {
        assertEquals(
            UsernameCompletionRoute.MORE,
            usernameCompletionRoute(
                IdentityCreationState.VOTING,
                usernameContestable = false,
                primaryUsernameContestable = true
            )
        )
    }

    @Test
    fun `non-contested single with a non-contestable primary still routes Home`() {
        assertEquals(
            UsernameCompletionRoute.HOME,
            usernameCompletionRoute(
                IdentityCreationState.DONE,
                usernameContestable = false,
                primaryUsernameContestable = false
            )
        )
    }
}
