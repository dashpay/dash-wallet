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

import org.bitcoinj.core.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-parsing tests for the `dashpay://user` QR payload codec.
 *
 * These vectors are the cross-platform wire contract — they mirror the iOS
 * `DashPayUserLinkTests.swift` byte for byte. If a case changes here it must
 * change there too (and vice versa).
 */
class DashPayUserLinkTest {

    // Any 32 bytes round-trip through base58 in the QR URI. Same fixture as
    // iOS: Data((0..<32).map { UInt8($0 &* 7 &+ 3) })
    private val identityId = ByteArray(32) { i -> (i * 7 + 3).toByte() }
    private val base58: String = Base58.encode(identityId)

    @Test
    fun encodeParseRoundTrip() {
        val link = DashPayUserLink(base58, "alice")
        assertEquals(link, DashPayUserLink.parse(link.uriString))
    }

    @Test
    fun parseStripsDashSuffixAndWhitespace() {
        val parsed = DashPayUserLink.parse("  dashpay://user?id=$base58&username=alice.dash\n")
        assertEquals("alice", parsed?.username)
        assertEquals(base58, parsed?.userId)
    }

    @Test
    fun parseIsCaseInsensitiveOnSchemeHostAndParamNames() {
        val parsed = DashPayUserLink.parse("DASHPAY://USER?ID=$base58&USERNAME=alice")
        assertEquals("alice", parsed?.username)
    }

    @Test
    fun parseRejectsForeignPayloads() {
        assertNull(DashPayUserLink.parse("dash:XoyzY6j9wkYp1yPe9GHmBdqSwSCmDHb2y7?amount=1"))
        assertNull(DashPayUserLink.parse("dashpay://invite?du=alice&cftx=abc"))
        assertNull(DashPayUserLink.parse("alice"))
        assertNull(DashPayUserLink.parse(""))
        // Missing either parameter.
        assertNull(DashPayUserLink.parse("dashpay://user?id=$base58"))
        assertNull(DashPayUserLink.parse("dashpay://user?username=alice"))
        // `.dash`-only label collapses to empty.
        assertNull(DashPayUserLink.parse("dashpay://user?id=$base58&username=.dash"))
    }

    @Test
    fun parseRejectsNonCanonicalUriShapes() {
        val canonicalQuery = "id=$base58&username=alice"
        // Userinfo, port, path, and fragment are not part of the wire contract.
        assertNull(DashPayUserLink.parse("dashpay://someone@user?$canonicalQuery"))
        assertNull(DashPayUserLink.parse("dashpay://someone:secret@user?$canonicalQuery"))
        assertNull(DashPayUserLink.parse("dashpay://user:1234?$canonicalQuery"))
        assertNull(DashPayUserLink.parse("dashpay://user/profile?$canonicalQuery"))
        assertNull(DashPayUserLink.parse("dashpay://user?$canonicalQuery#fragment"))
        // Each parameter exactly once, and nothing but id + username.
        assertNull(DashPayUserLink.parse("dashpay://user?$canonicalQuery&id=$base58"))
        assertNull(DashPayUserLink.parse("dashpay://user?$canonicalQuery&username=bob"))
        assertNull(DashPayUserLink.parse("dashpay://user?id=invalid&ID=$base58&username=alice"))
        assertNull(DashPayUserLink.parse("dashpay://user?$canonicalQuery&amount=1"))
    }

    @Test
    fun parseRejectsInvalidIdentityIds() {
        // Base58 alphabet excludes 0, O, I, l.
        assertNull(DashPayUserLink.parse("dashpay://user?id=0OIl&username=alice"))
        // Valid base58 but not 32 bytes.
        val short = Base58.encode(byteArrayOf(1, 2, 3))
        assertNull(DashPayUserLink.parse("dashpay://user?id=$short&username=alice"))
    }
}
