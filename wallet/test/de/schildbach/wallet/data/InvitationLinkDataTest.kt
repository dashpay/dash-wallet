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

package de.schildbach.wallet.data

import android.app.Application
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure parse/serialize + claim-path branch-selection logic for
 * [InvitationLinkData]: the shielded (L2) variant must round-trip, the
 * legacy L1 asset-lock variant must still parse byte-for-byte, and
 * [InvitationLinkData.isShielded] must pick the right claim branch purely
 * from the link contents (`osk` present ⇒ L2, else L1).
 *
 * Robolectric supplies the real `android.net.Uri` these accessors parse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class InvitationLinkDataTest {

    private companion object {
        // 32-byte one-time Orchard spending key, lowercase hex.
        const val ONE_TIME_KEY_HEX = "0011223344556677889900aabbccddeeff00112233445566778899aabbccddee"
        const val FUNDING_HEIGHT = 123456

        /** A representative L1 asset-lock invite link, built by hand (no dashj). */
        fun l1Link() = (
            "dashpay://invite" +
                "?du=alice" +
                "&assetlocktx=abc123" +
                "&pk=cP5WIf9ExampleWifKeyValue0000000000000000000000000000" +
                "&islock=deadbeef" +
                "&display-name=Alice"
            ).toUri()
    }

    // ── Shielded (L2) round-trip ──────────────────────────────────────

    @Test
    fun `shielded create then parse round-trips the one-time key and height`() {
        val data = InvitationLinkData.createShielded(
            username = "bob",
            displayName = "Bob",
            avatarUrl = "https://example.com/a.png",
            oneTimeKeyHex = ONE_TIME_KEY_HEX,
            fundingHeight = FUNDING_HEIGHT
        )

        assertTrue(InvitationLinkData.isValid(data.link))
        assertTrue(data.isShielded)
        assertEquals("bob", data.user)
        assertEquals("Bob", data.displayName)
        assertEquals("https://example.com/a.png", data.avatarUrl)
        assertEquals(ONE_TIME_KEY_HEX, data.oneTimeKey)
        assertEquals(FUNDING_HEIGHT, data.fundingHeight)
    }

    @Test
    fun `shielded link drops the L1 asset-lock params`() {
        val link = InvitationLinkData.createShielded(
            "bob", "", "", ONE_TIME_KEY_HEX, FUNDING_HEIGHT
        ).link
        assertFalse(link.queryParameterNames.contains("assetlocktx"))
        assertFalse(link.queryParameterNames.contains("pk"))
        assertFalse(link.queryParameterNames.contains("islock"))
    }

    @Test
    fun `shielded link with no funding height parses height as null but stays valid`() {
        val link = (
            "dashpay://invite?du=bob&osk=$ONE_TIME_KEY_HEX"
            ).toUri()
        val data = InvitationLinkData(link)
        assertTrue(InvitationLinkData.isValid(link))
        assertTrue(data.isShielded)
        assertNull(data.fundingHeight)
    }

    @Test
    fun `shielded link without a one-time key is invalid`() {
        val link = "dashpay://invite?du=bob&bh=10".toUri()
        assertFalse(InvitationLinkData.isValid(link))
    }

    // ── L1 variant unchanged ──────────────────────────────────────────

    @Test
    fun `legacy L1 link still parses and is not shielded`() {
        val data = InvitationLinkData(l1Link())
        assertTrue(InvitationLinkData.isValid(data.link))
        assertFalse(data.isShielded)
        assertEquals("alice", data.user)
        assertEquals("abc123", data.assetLockTx)
        assertEquals("deadbeef", data.instantSendLock)
    }

    @Test
    fun `L1 link missing the instant-send lock is invalid`() {
        val link = "dashpay://invite?du=alice&assetlocktx=abc123&pk=cP5W".toUri()
        assertFalse(InvitationLinkData.isValid(link))
    }

    // ── Branch selection (claim path picks L1 vs L2 by contents) ───────

    @Test
    fun `isShielded selects L2 for osk links and L1 for asset-lock links`() {
        assertTrue(InvitationLinkData(InvitationLinkData.createShielded(
            "bob", "", "", ONE_TIME_KEY_HEX, FUNDING_HEIGHT
        ).link).isShielded)
        assertFalse(InvitationLinkData(l1Link()).isShielded)
    }
}
