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

package de.schildbach.wallet.util

import org.bitcoinj.core.AddressFormatException as DashjAddressFormatException
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.payments.parsers.AddressFormatException
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.WifKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

/**
 * Differential tests: the dashj-free [WifKey] codec must behave exactly like dashj's
 * [DumpedPrivateKey] (22.0.3) on both Dash networks.
 */
class WifDashjParityTest {

    private val mainParams: NetworkParameters = MainNetParams.get()
    private val testParams: NetworkParameters = TestNet3Params.get()

    private fun networkFor(params: NetworkParameters): AddressNetwork =
        if (params === mainParams) AddressNetwork.DASH_MAINNET else AddressNetwork.DASH_TESTNET

    @Test
    fun versionBytes_matchDashjNetworkParameters() {
        // The mission-critical constants: verify against dashj itself, not documentation.
        assertEquals(mainParams.dumpedPrivateKeyHeader, AddressNetwork.DASH_MAINNET.dumpedPrivateKeyHeader)
        assertEquals(testParams.dumpedPrivateKeyHeader, AddressNetwork.DASH_TESTNET.dumpedPrivateKeyHeader)
        assertEquals(204, mainParams.dumpedPrivateKeyHeader)
        assertEquals(239, testParams.dumpedPrivateKeyHeader)
    }

    @Test
    fun encode_matchesDashj_forRealKeys() {
        val secureRandom = SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(4242L) }
        for (params in listOf(mainParams, testParams)) {
            val network = networkFor(params)
            repeat(20) {
                val key = ECKey(secureRandom) // compressed by default
                for (compressed in booleanArrayOf(true, false)) {
                    val ecKey = if (compressed) key else key.decompress()
                    val dashjWif = ecKey.getPrivateKeyEncoded(params).toBase58()
                    val ourWif = WifKey.encode(network, ecKey.privKeyBytes, compressed)
                    assertEquals(dashjWif, ourWif)
                }
            }
        }
    }

    @Test
    fun decode_matchesDashj_bothNetworks_bothFlags() {
        val random = Random(9876)
        for (params in listOf(mainParams, testParams)) {
            val network = networkFor(params)
            for (compressed in booleanArrayOf(true, false)) {
                repeat(20) {
                    val keyBytes = ByteArray(32).also { random.nextBytes(it) }
                    // Build the WIF with dashj, decode with both, compare everything.
                    val dashjWif = DumpedPrivateKey(params, keyBytes, compressed).toBase58()

                    val dashjDecoded = DumpedPrivateKey.fromBase58(params, dashjWif)
                    val ourDecoded = WifKey.decode(dashjWif, network)

                    assertEquals(dashjDecoded.isPubKeyCompressed, ourDecoded.compressed)
                    assertEquals(compressed, ourDecoded.compressed)
                    assertArrayEquals(keyBytes, ourDecoded.keyBytes)
                    assertEquals(dashjDecoded.toBase58(), ourDecoded.toBase58())
                    assertEquals(params.dumpedPrivateKeyHeader, ourDecoded.version)
                }
            }
        }
    }

    @Test
    fun decodedKeyBytes_matchDashjECKey() {
        val secureRandom = SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(777L) }
        repeat(10) {
            val key = ECKey(secureRandom)
            val wif = key.getPrivateKeyEncoded(mainParams).toBase58()
            val ours = WifKey.decode(wif, AddressNetwork.DASH_MAINNET)
            assertArrayEquals(key.privKeyBytes, ours.keyBytes)
            assertEquals(key.isCompressed, ours.compressed)
        }
    }

    @Test
    fun wrongNetwork_sameExceptionOnBothSides() {
        val keyBytes = ByteArray(32) { 5 }
        val mainnetWif = DumpedPrivateKey(mainParams, keyBytes, true).toBase58()

        assertThrows(DashjAddressFormatException.WrongNetwork::class.java) {
            DumpedPrivateKey.fromBase58(testParams, mainnetWif)
        }
        assertThrows(AddressFormatException.WrongNetwork::class.java) {
            WifKey.decode(mainnetWif, AddressNetwork.DASH_TESTNET)
        }

        val testnetWif = DumpedPrivateKey(testParams, keyBytes, false).toBase58()
        assertThrows(DashjAddressFormatException.WrongNetwork::class.java) {
            DumpedPrivateKey.fromBase58(mainParams, testnetWif)
        }
        assertThrows(AddressFormatException.WrongNetwork::class.java) {
            WifKey.decode(testnetWif, AddressNetwork.DASH_MAINNET)
        }
    }

    @Test
    fun networkAutodetect_matchesDashjNullParamsPath() {
        val keyBytes = ByteArray(32) { 13 }
        for (params in listOf(mainParams, testParams)) {
            val wif = DumpedPrivateKey(params, keyBytes, true).toBase58()
            val dashjResolved = DumpedPrivateKey.fromBase58(null, wif)
            val (ourKey, ourNetwork) = WifKey.decodeDash(wif)
            assertEquals(params.id, dashjResolved.parameters.id)
            assertEquals(params.id, ourNetwork.id)
            assertArrayEquals(keyBytes, ourKey.keyBytes)
        }
    }

    @Test
    fun corruptedInput_sameExceptionFamilies() {
        val wif = DumpedPrivateKey(mainParams, ByteArray(32) { 1 }, true).toBase58()

        // bad checksum
        val corrupted = wif.substring(0, wif.length - 1) + (if (wif.last() == '1') '2' else '1')
        assertThrows(DashjAddressFormatException::class.java) {
            DumpedPrivateKey.fromBase58(mainParams, corrupted)
        }
        assertThrows(AddressFormatException::class.java) { WifKey.decode(corrupted) }

        // bad payload length: 31 key bytes under the mainnet version byte
        val shortPayload = org.bitcoinj.core.Base58.encodeChecked(204, ByteArray(31))
        assertThrows(DashjAddressFormatException.InvalidDataLength::class.java) {
            DumpedPrivateKey.fromBase58(mainParams, shortPayload)
        }
        assertThrows(AddressFormatException.InvalidDataLength::class.java) {
            WifKey.decode(shortPayload)
        }
    }

    @Test
    fun wrongVersionAndWrongLength_versionCheckedBeforeLength_likeDashj() {
        // 31-byte payload under the mainnet version byte, decoded against testnet: dashj's
        // fromBase58(params, s) checks the version byte BEFORE the payload length, so this must
        // be WrongNetwork on both sides, never InvalidDataLength.
        val mainnetShort = org.bitcoinj.core.Base58.encodeChecked(204, ByteArray(31))
        assertThrows(DashjAddressFormatException.WrongNetwork::class.java) {
            DumpedPrivateKey.fromBase58(testParams, mainnetShort)
        }
        assertThrows(AddressFormatException.WrongNetwork::class.java) {
            WifKey.decode(mainnetShort, AddressNetwork.DASH_TESTNET)
        }

        // ...and the mirror image: testnet version byte, bad length, decoded against mainnet.
        val testnetShort = org.bitcoinj.core.Base58.encodeChecked(239, ByteArray(31))
        assertThrows(DashjAddressFormatException.WrongNetwork::class.java) {
            DumpedPrivateKey.fromBase58(mainParams, testnetShort)
        }
        assertThrows(AddressFormatException.WrongNetwork::class.java) {
            WifKey.decode(testnetShort, AddressNetwork.DASH_MAINNET)
        }
    }

    @Test
    fun unknownVersionAndWrongLength_autodetect_prefixCheckedBeforeLength_likeDashj() {
        // Unknown version byte (Bitcoin mainnet's 0x80 = 128) with a 31-byte payload via the
        // autodetect path: dashj's fromBase58(null, s) rejects the prefix before it ever
        // constructs the key, so both sides must throw InvalidPrefix, not InvalidDataLength.
        val foreignShort = org.bitcoinj.core.Base58.encodeChecked(128, ByteArray(31))
        assertThrows(DashjAddressFormatException.InvalidPrefix::class.java) {
            DumpedPrivateKey.fromBase58(null, foreignShort)
        }
        assertThrows(AddressFormatException.InvalidPrefix::class.java) {
            WifKey.decodeDash(foreignShort)
        }

        // A KNOWN Dash version byte with a bad length must still reach the length check on
        // both sides (version match happens first, then the constructor validates length).
        val testnetShort = org.bitcoinj.core.Base58.encodeChecked(239, ByteArray(31))
        assertThrows(DashjAddressFormatException.InvalidDataLength::class.java) {
            DumpedPrivateKey.fromBase58(null, testnetShort)
        }
        assertThrows(AddressFormatException.InvalidDataLength::class.java) {
            WifKey.decodeDash(testnetShort)
        }
    }
}
