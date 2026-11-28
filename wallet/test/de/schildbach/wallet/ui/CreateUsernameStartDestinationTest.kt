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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM tests for [resolveCreateUsernameStartDestination] — the Join
 * DashPay flow-persistence requirement: the full designed flow (welcome →
 * payment sheets) shows on EVERY create-path entry until the wallet has an
 * identity, no matter how often the info screens were viewed; the old
 * "seen once" shortcut only survives for the invite/reuse re-entry arms.
 */
class CreateUsernameStartDestinationTest {

    @Test
    fun createPath_alwaysStartsAtWelcome_regardlessOfInfoShownFlag() {
        for (infoShown in listOf(false, true)) {
            assertEquals(
                "infoShown=$infoShown",
                CreateUsernameStartDestination.WELCOME,
                resolveCreateUsernameStartDestination(
                    usernameInVoting = false,
                    usingInvite = false,
                    identityCreationStarted = false,
                    dashPayInfoShown = infoShown
                )
            )
        }
    }

    @Test
    fun infoShownWriteIsHarmlessOnTheCreatePath() {
        // The UsernameVotingInfoFragment write (dashPayInfoShown = true)
        // must not flip the create-path start destination: same wallet
        // state before and after viewing the info screen → same WELCOME.
        val before = resolveCreateUsernameStartDestination(
            usernameInVoting = false,
            usingInvite = false,
            identityCreationStarted = false,
            dashPayInfoShown = false
        )
        val after = resolveCreateUsernameStartDestination(
            usernameInVoting = false,
            usingInvite = false,
            identityCreationStarted = false,
            dashPayInfoShown = true
        )
        assertEquals(CreateUsernameStartDestination.WELCOME, before)
        assertEquals(before, after)
    }

    @Test
    fun usernameInVoting_keepsTheVotingDetailsScreen() {
        // Post-payment, out of the create path — never the welcome flow.
        for (invite in listOf(false, true)) {
            for (started in listOf(false, true)) {
                for (infoShown in listOf(false, true)) {
                    assertEquals(
                        CreateUsernameStartDestination.VOTING_REQUEST_DETAILS,
                        resolveCreateUsernameStartDestination(
                            usernameInVoting = true,
                            usingInvite = invite,
                            identityCreationStarted = started,
                            dashPayInfoShown = infoShown
                        )
                    )
                }
            }
        }
    }

    @Test
    fun inviteDeepLink_keepsLegacyBehavior() {
        // First entry: welcome; after the info screen was seen: straight
        // to the request screen (the invite pays the fee — no payment
        // sheets are involved).
        assertEquals(
            CreateUsernameStartDestination.WELCOME,
            resolveCreateUsernameStartDestination(
                usernameInVoting = false,
                usingInvite = true,
                identityCreationStarted = false,
                dashPayInfoShown = false
            )
        )
        assertEquals(
            CreateUsernameStartDestination.REQUEST_USERNAME,
            resolveCreateUsernameStartDestination(
                usernameInVoting = false,
                usingInvite = true,
                identityCreationStarted = false,
                dashPayInfoShown = true
            )
        )
    }

    @Test
    fun identityInFlight_keepsLegacyBehavior() {
        // Locked/lost-vote reuse or a creation already started: the state
        // machine owns the flow — no forced welcome re-entry.
        assertEquals(
            CreateUsernameStartDestination.WELCOME,
            resolveCreateUsernameStartDestination(
                usernameInVoting = false,
                usingInvite = false,
                identityCreationStarted = true,
                dashPayInfoShown = false
            )
        )
        assertEquals(
            CreateUsernameStartDestination.REQUEST_USERNAME,
            resolveCreateUsernameStartDestination(
                usernameInVoting = false,
                usingInvite = false,
                identityCreationStarted = true,
                dashPayInfoShown = true
            )
        )
    }
}
