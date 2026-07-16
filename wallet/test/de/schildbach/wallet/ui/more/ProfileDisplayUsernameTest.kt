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

    // DPNS homoglyph fold used by the real Names.normalizeString (o->0, l/i->1).
    private val normalize: (String) -> String = { s ->
        s.replace('o', '0').replace('l', '1').replace('i', '1')
    }

    @Test
    fun `dual first render (profile still primary) shows the normalized secondary`() {
        // Profile not yet refreshed to the secondary — activeUsername (the
        // normalized secondary) is the best available value.
        assertEquals(
            "b0b2",
            profileDisplayUsername(
                profileUsername = "alice",           // still the primary
                identityActiveUsername = "b0b2",     // normalized secondary
                showSecondaryUsername = true,
                normalize = normalize
            )
        )
    }

    @Test
    fun `dual after refresh shows the DISPLAY-form secondary, not the normalized label`() {
        // Profile refreshed to the secondary's display label ("bob2"); its
        // normalized form ("b0b2") matches activeUsername → prefer the display.
        assertEquals(
            "bob2",
            profileDisplayUsername(
                profileUsername = "bob2",            // secondary DISPLAY label
                identityActiveUsername = "b0b2",     // normalized secondary
                showSecondaryUsername = true,
                normalize = normalize
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
                showSecondaryUsername = false,
                normalize = normalize
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
                showSecondaryUsername = false,
                normalize = normalize
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
                showSecondaryUsername = true,
                normalize = normalize
            )
        )
    }
}
