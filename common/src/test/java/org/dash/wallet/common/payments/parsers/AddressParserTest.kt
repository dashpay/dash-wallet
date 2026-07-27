/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.common.payments.parsers

import org.bitcoinj.params.MainNetParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressParserTest {
    @Test
    fun dashAddressTest() {
        val parser = AddressParser(AddressParser.PATTERN_BITCOIN_ADDRESS, MainNetParams.get())

        assertTrue(parser.exactMatch("XssjzLKgsfATYGqTQmiJURQzeKdpL5K1k3"))

        assertEquals(
            1,
            parser.findAll("XssjzLKgsfATYGqTQmiJURQzeKdpL5K1k3").size
        )

        assertEquals(
            2,
            parser.findAll(
                """
                Here is the first address XssjzLKgsfATYGqTQmiJURQzeKdpL5K1k3 and the second: 
                XssjzLKgsfATYGqTQmiJURQzeKdpL5K1k3
                """
            ).size
        )
    }

    @Test
    fun bitcoinAddressTest() {
        val network = BitcoinMainNetParams()
        val parser = BitcoinAddressParser(network)

        assertTrue(parser.exactMatch("183axN6F7ZjwayiJPjjwJgWGas6J9mtfi"))
        assertTrue(parser.exactMatch("34Me5SAG8W8Bf2LxGfPiqVZRKKV1VL1hmW"))
        assertTrue(parser.exactMatch("bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0"))
        assertTrue(parser.exactMatch("bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297"))
        assertTrue(parser.exactMatch("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"))
        SegwitAddress.fromBech32(network, "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297")
        SegwitAddress.fromBech32(network, "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0")

        // All-uppercase bech32 is valid per BIP-173 (QR codes use it for alphanumeric mode).
        // First one is the BIP-173 uppercase test vector.
        assertTrue(parser.exactMatch("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"))
        assertTrue(parser.exactMatch("BC1QXHGNNP745ZRYN2UD8HM6K3MYGKKPKM35020JS0"))
        assertTrue(parser.exactMatch("BC1P5D7RJQ7G6RDK2YHZKS9SMLAQTEDR4DEKQ08GE8ZTWAC72SFR9RUSXG3297"))
        SegwitAddress.fromBech32(network, "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")

        // Mixed case is invalid per BIP-173.
        assertFalse(parser.exactMatch("bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020JS0"))
        assertFalse(parser.exactMatch("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3t4"))

        assertEquals(
            5,
            parser.findAll(
                """
                Here is the first address 183axN6F7ZjwayiJPjjwJgWGas6J9mtfi
                and the second: 34Me5SAG8W8Bf2LxGfPiqVZRKKV1VL1hmW
                \n\n bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0
                \n bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297
                \n BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4
                """
            ).size
        )
    }

    @Test
    fun ethereumAddressTest() {
        val parser = AddressParser.getEthereumAddressParser()

        assertTrue(parser.exactMatch("0x51a1449b3B6D635EddeC781cD47a99221712De97"))
        assertTrue(parser.exactMatch("0xa895f5E48e91BD314ab146bD235b4345f657f497"))

        assertEquals(
            2,
            parser.findAll(
                """
                Here is the first address 0x51a1449b3B6D635EddeC781cD47a99221712De97 and the second: 
                0xa895f5E48e91BD314ab146bD235b4345f657f497"
                """
            ).size
        )
    }

    @Test
    fun normalizeCaseTest() {
        val bitcoinParser = BitcoinAddressParser(BitcoinMainNetParams())

        // All-caps bech32 (QR alphanumeric mode) canonicalizes to lowercase.
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            bitcoinParser.normalizeCase("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        )
        // Already lowercase, mixed case, and case-significant Base58 pass through unchanged.
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            bitcoinParser.normalizeCase("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        )
        assertEquals(
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3t4",
            bitcoinParser.normalizeCase("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3t4")
        )
        assertEquals(
            "34Me5SAG8W8Bf2LxGfPiqVZRKKV1VL1hmW",
            bitcoinParser.normalizeCase("34Me5SAG8W8Bf2LxGfPiqVZRKKV1VL1hmW")
        )
        // Uppercased Base58 doesn't lowercase into a valid address — untouched.
        assertEquals(
            "34ME5SAG8W8BF2LXGFPIQVZRKKV1VL1HMW",
            bitcoinParser.normalizeCase("34ME5SAG8W8BF2LXGFPIQVZRKKV1VL1HMW")
        )

        val runeParser = Bech32AddressParser("thor", 38, null)
        assertEquals(
            "thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws",
            runeParser.normalizeCase("THOR166N4W5039MEULFA3P6YDG60VE6UEAC7TLT0JWS")
        )
    }

    @Test
    fun bech32AddressTest() {
        val parser = Bech32AddressParser("kujira", 38, null)

        assertTrue(parser.exactMatch("kujira1r8egcurpwxftegr07gjv9gwffw4fk00960dj4f"))
        assertTrue(parser.exactMatch("kujira1377jxt6t0jrkk47thc86udxfxnvqkhj7evmd99"))

        // All-uppercase bech32 is valid per BIP-173; mixed case is not.
        assertTrue(parser.exactMatch("KUJIRA1R8EGCURPWXFTEGR07GJV9GWFFW4FK00960DJ4F"))
        assertFalse(parser.exactMatch("kujira1R8EGCURPWXFTEGR07GJV9GWFFW4FK00960DJ4F"))

        assertEquals(
            2,
            parser.findAll(
                """
                Here is the first address kujira1r8egcurpwxftegr07gjv9gwffw4fk00960dj4f and the second: 
                kujira1377jxt6t0jrkk47thc86udxfxnvqkhj7evmd99
                """
            ).size
        )

        val runeParser = Bech32AddressParser("thor", 38, null)

        assertTrue(runeParser.exactMatch("thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws"))
        assertTrue(runeParser.exactMatch("thor1ap5vn4svwkpch2c9jm7hlpr2pj47e62xwpcvtw"))
        assertTrue(runeParser.exactMatch("THOR166N4W5039MEULFA3P6YDG60VE6UEAC7TLT0JWS"))
        assertFalse(runeParser.exactMatch("THOR166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws"))

        assertEquals(
            2,
            runeParser.findAll(
                """
                Here is the first address thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws and the second: 
                thor1ap5vn4svwkpch2c9jm7hlpr2pj47e62xwpcvtw
                """
            ).size
        )
    }
}
