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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.integrations.maya.payments.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressParserTest {

    @Test
    fun tronAddressTest() {
        val parser = TronAddressParser()

        // Valid Base58Check addresses: 34 chars, leading 'T'.
        assertTrue(parser.exactMatch("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"))
        assertTrue(parser.exactMatch("TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7"))

        // Wrong prefix (must start with 'T').
        assertFalse(parser.exactMatch("1R7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"))
        // Too short (33 chars total).
        assertFalse(parser.exactMatch("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6"))
        // Contains a non-Base58 character ('0').
        assertFalse(parser.exactMatch("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjL0jt"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                Here is the first address TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t and the second:
                TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7
                """
            ).size
        )
    }

    @Test
    fun tonAddressTest() {
        val parser = TonAddressParser()

        // Valid user-friendly Base64URL form (48 chars).
        assertTrue(parser.exactMatch("EQDrjaLahLkMB-hMCmkzOyBuHJ139ZUYmPHu6RRBKnbdLIYI"))
        // Valid raw form: <workchain>:<256-bit-hex>.
        assertTrue(
            parser.exactMatch("0:fcb91a3a3816d0f7b8c2c76108b8a9bc5568b7060c1e2bd75d8aaf0e3d1c7c00")
        )
        assertTrue(
            parser.exactMatch("-1:fcb91a3a3816d0f7b8c2c76108b8a9bc5568b7060c1e2bd75d8aaf0e3d1c7c00")
        )

        // Too short (47 chars).
        assertFalse(parser.exactMatch("EQDrjaLahLkMB-hMCmkzOyBuHJ139ZUYmPHu6RRBKnbdLIY"))
        // Standard Base64 chars ('+', '=') are not valid Base64URL.
        assertFalse(parser.exactMatch("EQDrjaLahLkMB+hMCmkzOyBuHJ139ZUYmPHu6RRBKnbdLI="))
        // Raw form with truncated hex.
        assertFalse(parser.exactMatch("0:fcb91a3a3816d0f7"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                Here is the first address EQDrjaLahLkMB-hMCmkzOyBuHJ139ZUYmPHu6RRBKnbdLIYI and the second:
                0:fcb91a3a3816d0f7b8c2c76108b8a9bc5568b7060c1e2bd75d8aaf0e3d1c7c00
                """
            ).size
        )
    }

    @Test
    fun cardanoAddressTest() {
        val parser = CardanoAddressParser()

        // Shelley Bech32 (addr1...) and legacy Byron Base58 (Ae2.../DdzFF...).
        assertTrue(
            parser.exactMatch(
                "addr1q9c8e2wjwj4uxsmrk2lqkkpqalwzvxgyx7uxjkfeg7xc3xa07c6qzwrcfh2x4f4z4uyez5lpd07v3jkh3ttn0xc2x7qspewtaa"
            )
        )
        assertTrue(parser.exactMatch("Ae2tdPwUPEZ4YjgvykNpoFeYUxoyhNj2kg8KfKWN2FizsSpLUPv68MpTVDo"))

        // Non-Bech32 character.
        assertFalse(parser.exactMatch("addr1bad+char"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                first addr1q9c8e2wjwj4uxsmrk2lqkkpqalwzvxgyx7uxjkfeg7xc3xa07c6qzwrcfh2x4f4z4uyez5lpd07v3jkh3ttn0xc2x7qspewtaa
                second Ae2tdPwUPEZ4YjgvykNpoFeYUxoyhNj2kg8KfKWN2FizsSpLUPv68MpTVDo
                """
            ).size
        )
    }

    @Test
    fun nearAddressTest() {
        val parser = NearAddressParser()

        // Named account and 64-char implicit (hex) account.
        assertTrue(parser.exactMatch("alice.near"))
        assertTrue(
            parser.exactMatch("98793cd91a3f870fb126f66285808c7e094afcfc4eda8a970f6648cdf0dbd6de")
        )

        // Named accounts are lowercase only; a single label has no '.'.
        assertFalse(parser.exactMatch("Alice.NEAR"))
        assertFalse(parser.exactMatch("alice"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                first alice.near
                second 98793cd91a3f870fb126f66285808c7e094afcfc4eda8a970f6648cdf0dbd6de
                """
            ).size
        )
    }

    @Test
    fun solanaAddressTest() {
        val parser = SolanaAddressParser()

        // Base58 ed25519 public keys, 32-44 chars.
        assertTrue(parser.exactMatch("DYw8jCTfwHNRJhhmFcbXvVDTqWMEVFBX6ZKUmG5CNSKK"))
        assertTrue(parser.exactMatch("So11111111111111111111111111111111111111112"))

        // 31 chars (below minimum) and a non-Base58 alphabet.
        assertFalse(parser.exactMatch("1111111111111111111111111111111"))
        assertFalse(parser.exactMatch("0OIl000000000000000000000000000000"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                first DYw8jCTfwHNRJhhmFcbXvVDTqWMEVFBX6ZKUmG5CNSKK
                second So11111111111111111111111111111111111111112
                """
            ).size
        )
    }

    @Test
    fun starknetAddressTest() {
        val parser = StarknetAddressParser()

        // 0x + 1..64 hex chars (252-bit felt).
        assertTrue(
            parser.exactMatch("0x04718f5a0fc34cc1af16a1cdee98ffb20c31f5cd61d6ab07201858f4287c938d")
        )
        assertTrue(parser.exactMatch("0xdeadbeef"))

        // Missing 0x prefix and more than 64 hex chars.
        assertFalse(parser.exactMatch("04718f5a"))
        assertFalse(parser.exactMatch("0x" + "a".repeat(65)))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                first 0x04718f5a0fc34cc1af16a1cdee98ffb20c31f5cd61d6ab07201858f4287c938d
                second 0xdeadbeef
                """
            ).size
        )
    }

    @Test
    fun suiAddressTest() {
        val parser = SuiAddressParser()

        // 0x + exactly 64 hex chars (32 bytes).
        assertTrue(
            parser.exactMatch("0xd1b72982e40348d069bb1ff701e634c117bb5f741f44dff91e472d3b01461e55")
        )

        // 63 and 65 hex chars (off by one either way), and missing prefix.
        assertFalse(parser.exactMatch("0x" + "a".repeat(63)))
        assertFalse(parser.exactMatch("0x" + "a".repeat(65)))
        assertFalse(parser.exactMatch("d1b72982e40348d069bb1ff701e634c117bb5f741f44dff91e472d3b01461e55"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                first 0xd1b72982e40348d069bb1ff701e634c117bb5f741f44dff91e472d3b01461e55
                second 0x${"a".repeat(64)}
                """
            ).size
        )
    }

    @Test
    fun xrpAddressTest() {
        val parser = XrpAddressParser()

        // Base58Check, leading 'r'.
        assertTrue(parser.exactMatch("rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh"))
        assertTrue(parser.exactMatch("rUocf1ixKzTuEe34kmVhRvGqNCofY1NJzV"))

        // Wrong prefix and a non-Base58 character ('0').
        assertFalse(parser.exactMatch("XEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh"))
        assertFalse(parser.exactMatch("r0Eb8TK3gBgk5auZkwc6sHnwrGVJH8"))
        assertFalse(parser.exactMatch(""))

        assertEquals(
            2,
            parser.findAll(
                """
                first rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh
                second rUocf1ixKzTuEe34kmVhRvGqNCofY1NJzV
                """
            ).size
        )
    }

    @Test
    fun zcashAddressTest() {
        val parser = ZcashAddressParser()

        // Transparent t1 (P2PKH) and t3 (P2SH).
        assertTrue(parser.exactMatch("t1K79TgQbqu74d6rBmsMu2oFEXEwAmdYiT7"))
        assertTrue(parser.exactMatch("t3Vz22vK5z2LcKEdg16Yv4FFneEL1zg9ojd"))

        // Sapling (zs1...) and unified (u1...). These parsers validate by pattern only
        // (no checksum), so length-exact, Bech32-charset fixtures exercise each branch.
        val sapling = "zs1qpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2"
        val unified = "u1qpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54kh"
        assertTrue(parser.exactMatch(sapling))
        assertTrue(parser.exactMatch(unified))

        // 't2' is not a valid transparent prefix (only t1 / t3).
        assertFalse(parser.exactMatch("t2K79TgQbqu74d6rBmsMu2oFEXEwAmdYiT7"))
        assertFalse(parser.exactMatch(""))

        // Combined parser finds transparent + sapling + unified forms.
        assertEquals(
            3,
            parser.findAll(
                """
                transparent t1K79TgQbqu74d6rBmsMu2oFEXEwAmdYiT7
                sapling $sapling
                unified $unified
                """
            ).size
        )
    }
}