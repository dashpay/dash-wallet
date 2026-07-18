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

package org.dash.wallet.common.payments.parsers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Standalone tests of the WIF codec. Differential tests against dashj's `DumpedPrivateKey`
 * live in the wallet module (`WifDashjParityTest`).
 */
class WifKeyTest {

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    @Test
    fun networkHeaders_matchDashj() {
        // Verified against dashj 22.0.3 NetworkParameters.getDumpedPrivateKeyHeader()
        // (MainNetParams / TestNet3Params / DevNetParams constructor bytecode).
        assertEquals(204, AddressNetwork.DASH_MAINNET.dumpedPrivateKeyHeader)
        assertEquals(239, AddressNetwork.DASH_TESTNET.dumpedPrivateKeyHeader)
        assertEquals(239, AddressNetwork.DASH_DEVNET.dumpedPrivateKeyHeader)
        assertEquals(128, AddressNetwork.BITCOIN_MAINNET.dumpedPrivateKeyHeader)
    }

    @Test
    fun bitcoinWikiVector_uncompressed() {
        // Classic Bitcoin wiki WIF test vector (version byte 0x80).
        val wif = "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
        val keyHex = "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d"
        val key = WifKey.decode(wif, AddressNetwork.BITCOIN_MAINNET)
        assertEquals(128, key.version)
        assertFalse(key.compressed)
        assertArrayEquals(unhex(keyHex), key.keyBytes)
        assertEquals(wif, key.toBase58())
        assertEquals(wif, WifKey.encode(AddressNetwork.BITCOIN_MAINNET, unhex(keyHex), false))
    }

    @Test
    fun roundtrip_bothDashNetworks_bothCompressionFlags() {
        val random = Random(1234)
        for (network in listOf(AddressNetwork.DASH_MAINNET, AddressNetwork.DASH_TESTNET)) {
            for (compressed in booleanArrayOf(false, true)) {
                repeat(25) {
                    val keyBytes = ByteArray(32).also { random.nextBytes(it) }
                    val wif = WifKey.encode(network, keyBytes, compressed)
                    val decoded = WifKey.decode(wif, network)
                    assertEquals(network.dumpedPrivateKeyHeader, decoded.version)
                    assertEquals(compressed, decoded.compressed)
                    assertArrayEquals(keyBytes, decoded.keyBytes)
                    assertEquals(wif, decoded.toBase58())

                    val (anyKey, anyNetwork) = WifKey.decodeDash(wif)
                    assertEquals(network.dumpedPrivateKeyHeader, anyNetwork.dumpedPrivateKeyHeader)
                    assertArrayEquals(keyBytes, anyKey.keyBytes)
                }
            }
        }
    }

    @Test
    fun decode_wrongNetwork_throws() {
        val keyBytes = ByteArray(32) { 7 }
        val mainnetWif = WifKey.encode(AddressNetwork.DASH_MAINNET, keyBytes, true)
        assertThrows(AddressFormatException.WrongNetwork::class.java) {
            WifKey.decode(mainnetWif, AddressNetwork.DASH_TESTNET)
        }
        val testnetWif = WifKey.encode(AddressNetwork.DASH_TESTNET, keyBytes, true)
        assertThrows(AddressFormatException.WrongNetwork::class.java) {
            WifKey.decode(testnetWif, AddressNetwork.DASH_MAINNET)
        }
    }

    @Test
    fun decodeDash_rejectsForeignVersionByte() {
        // Bitcoin mainnet WIF (0x80) is on no Dash network.
        val wif = WifKey.encode(AddressNetwork.BITCOIN_MAINNET, ByteArray(32) { 7 }, false)
        assertThrows(AddressFormatException.InvalidPrefix::class.java) { WifKey.decodeDash(wif) }
    }

    @Test
    fun decode_rejectsBadChecksumAndBadLength() {
        val wif = WifKey.encode(AddressNetwork.DASH_MAINNET, ByteArray(32) { 9 }, false)
        val corrupted = wif.substring(0, wif.length - 1) + (if (wif.last() == '1') '2' else '1')
        assertThrows(AddressFormatException::class.java) { WifKey.decode(corrupted) }

        // 31-byte payload is not a private key
        val tooShort = Base58.encodeChecked(204, ByteArray(31))
        assertThrows(AddressFormatException.InvalidDataLength::class.java) { WifKey.decode(tooShort) }
        // 34-byte payload neither
        val tooLong = Base58.encodeChecked(204, ByteArray(34))
        assertThrows(AddressFormatException.InvalidDataLength::class.java) { WifKey.decode(tooLong) }
    }

    @Test
    fun compressedFlag_requiresTrailing01_likeDashj() {
        // A 33-byte payload whose last byte is not 0x01 decodes, but is not "compressed"
        // (mirrors DumpedPrivateKey.isPubKeyCompressed()).
        val payload = ByteArray(33).also { it[32] = 2 }
        val wif = Base58.encodeChecked(204, payload)
        val key = WifKey.decode(wif, AddressNetwork.DASH_MAINNET)
        assertFalse(key.compressed)
        assertTrue(WifKey.decode(WifKey.encode(AddressNetwork.DASH_MAINNET, ByteArray(32), true)).compressed)
    }

    @Test
    fun encode_rejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException::class.java) {
            WifKey.encode(AddressNetwork.DASH_MAINNET, ByteArray(31), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WifKey.encode(AddressNetwork.DASH_MAINNET, ByteArray(33), false)
        }
    }
}
