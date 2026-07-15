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

package de.schildbach.wallet.ui.more

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM tests for [profileDisplayUsername] — the name shown under the
 * More-screen avatar (Bug 2). A dual (contested + instant) username in
 * voting must show the confirmed INSTANT/secondary name immediately after
 * creation (matching the re-entry render), not the not-yet-owned CONTESTED
 * primary the fresh profile was seeded with; single-username wallets keep
 * showing the profile's own name.
 */
class ProfileDisplayUsernameTest {

    @Test
    fun `dual username in voting shows the confirmed instant name`() {
        // Profile seeded with the contested primary; identity reports the
        // secondary is confirmed (showSecondaryUsername) → use the secondary.
        assertEquals(
            "alice2",
            profileDisplayUsername(
                profileUsername = "alice",
                identityActiveUsername = "alice2",
                showSecondaryUsername = true
            )
        )
    }

    @Test
    fun `single username uses the profile name`() {
        assertEquals(
            "alice",
            profileDisplayUsername(
                profileUsername = "alice",
                identityActiveUsername = "alice",
                showSecondaryUsername = false
            )
        )
    }

    @Test
    fun `no confirmed secondary falls back to the profile name`() {
        // votingInProgress but the secondary is not CONFIRMED yet
        // (showSecondaryUsername false) — nothing else is owned, keep the
        // profile's name rather than blanking the line.
        assertEquals(
            "alice",
            profileDisplayUsername(
                profileUsername = "alice",
                identityActiveUsername = null,
                showSecondaryUsername = false
            )
        )
    }

    @Test
    fun `missing active username falls back to the profile name`() {
        // Defensive: showSecondaryUsername true but no usable active name —
        // never render an empty username under the avatar.
        assertEquals(
            "alice",
            profileDisplayUsername(
                profileUsername = "alice",
                identityActiveUsername = "",
                showSecondaryUsername = true
            )
        )
    }
}
