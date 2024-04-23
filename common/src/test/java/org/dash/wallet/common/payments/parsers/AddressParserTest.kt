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
                XssjzLKgsfATYGqTQmiJURQzeKdpL5K1k3" // ktlint-disable max-line-length
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
        SegwitAddress.fromBech32(network, "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297")

        assertEquals(
            4,
            parser.findAll(
                """
                Here is the first address 183axN6F7ZjwayiJPjjwJgWGas6J9mtfi
                and the second: 34Me5SAG8W8Bf2LxGfPiqVZRKKV1VL1hmW
                \n\n bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0
                \n bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297
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
    fun bech32AddressTest() {
        val parser = Bech32AddressParser("kujira", 38, null)

        assertTrue(parser.exactMatch("kujira1r8egcurpwxftegr07gjv9gwffw4fk00960dj4f"))
        assertTrue(parser.exactMatch("kujira1377jxt6t0jrkk47thc86udxfxnvqkhj7evmd99"))

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
