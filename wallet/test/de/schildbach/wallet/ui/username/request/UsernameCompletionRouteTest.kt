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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the post-completion routing decision
 * ([usernameCompletionRoute]) and the usable-username signal it keys off
 * ([hasUsableUsername]). A contested / in-voting username has no home welcome
 * tile, so ON ITS OWN it must land on the More screen's voting tile — but once
 * the identity owns a registered instant name the wallet is usable and the
 * completion returns to Home; everything else returns to Home.
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

    // --- a registered INSTANT name outranks the pending contested one ---

    @Test
    fun `a usable instant username routes Home even with the contested name in voting`() {
        // Brian, 11.10.54 (S22): contested "gffh" in VOTING + instant
        // "gffh-2" already registered landed on More. The wallet IS usable,
        // so the completion belongs on Home.
        assertEquals(
            UsernameCompletionRoute.HOME,
            usernameCompletionRoute(
                IdentityCreationState.VOTING,
                usernameContestable = false,
                primaryUsernameContestable = true,
                usableUsernameActive = true
            )
        )
    }

    @Test
    fun `a usable instant username outranks the finishing screen's own contestable flag`() {
        assertEquals(
            UsernameCompletionRoute.HOME,
            usernameCompletionRoute(
                IdentityCreationState.USERNAME_REGISTERING,
                usernameContestable = true,
                usableUsernameActive = true
            )
        )
    }

    @Test
    fun `only a pending contested request still routes More`() {
        assertEquals(
            UsernameCompletionRoute.MORE,
            usernameCompletionRoute(
                IdentityCreationState.VOTING,
                usernameContestable = false,
                primaryUsernameContestable = true,
                usableUsernameActive = false
            )
        )
    }

    // --- hasUsableUsername: what counts as a live instant name ---

    @Test
    fun `a registered secondary name is usable`() {
        assertTrue(hasUsableUsername(IdentityCreationState.VOTING, "gffh-2"))
        assertTrue(hasUsableUsername(IdentityCreationState.USERNAME_SECONDARY_REGISTERED, "gffh-2"))
        assertTrue(hasUsableUsername(IdentityCreationState.DONE, "gffh-2"))
    }

    @Test
    fun `a secondary name still registering is not usable yet`() {
        // The secondary pass rewinds to REGISTERING on failure, so anything
        // at or below it is NOT proof the instant name is on chain.
        assertFalse(hasUsableUsername(IdentityCreationState.USERNAME_SECONDARY_REGISTERING, "gffh-2"))
        assertFalse(hasUsableUsername(IdentityCreationState.PREORDER_SECONDARY_REGISTERING, "gffh-2"))
        assertFalse(hasUsableUsername(IdentityCreationState.IDENTITY_REGISTERED, "gffh-2"))
    }

    @Test
    fun `a single-name creation has no usable instant name however far the state advanced`() {
        // A single contested creation SKIPS the secondary pass and still
        // advances past the secondary marker — the name must be present too.
        assertFalse(hasUsableUsername(IdentityCreationState.VOTING, null))
        assertFalse(hasUsableUsername(IdentityCreationState.VOTING, ""))
    }

    @Test
    fun `an unknown creation state is never treated as usable`() {
        assertFalse(hasUsableUsername(null, "gffh-2"))
    }
}
