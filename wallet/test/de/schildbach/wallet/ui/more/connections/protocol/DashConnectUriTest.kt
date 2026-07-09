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

package de.schildbach.wallet.ui.more.connections.protocol

import org.bitcoinj.core.Base58
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Utils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class DashConnectUriTest {

    private fun hex(s: String): ByteArray = Utils.HEX.decode(s)

    // SERIALIZED_REQUEST_HEX from the DApp fixtures:
    //   01 || APP_PUBLIC_KEY(33) || cd*32 || 0e || "Login to Yappr"
    private val serializedRequestHex =
        "01" +
            "031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" +
            "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
            "0e" +
            "4c6f67696e20746f205961707072"

    private fun appPublicKey(): ByteArray =
        ECKey.fromPrivate(BigInteger(1, ByteArray(32) { 0x01 }), true).pubKey

    // ── serialization matches the exact fixture ────────────────────────────────────
    @Test
    fun serializedFixture_decodesToExpectedFields() {
        val payload = hex(serializedRequestHex)
        // Build the URI from the fixture payload (testnet), then parse it back.
        val uri = "dash-key:" + Base58.encode(payload) + "?n=t&v=1"
        val request = DashConnectUri.parseKeyRequest(uri)

        assertArrayEquals(appPublicKey(), request.appEphemeralPubKey)
        assertArrayEquals(ByteArray(32) { 0xcd.toByte() }, request.contractId)
        assertEquals("Login to Yappr", request.label)
        assertEquals(DashConnectNetwork.TESTNET, request.network)
    }

    @Test
    fun appPublicKeyInFixture_isTheCompressedKeyForPriv01() {
        // Confirms our understanding of SERIALIZED_REQUEST_HEX byte range [1,34).
        assertArrayEquals(appPublicKey(), hex(serializedRequestHex).copyOfRange(1, 34))
    }

    // ── network codes ──────────────────────────────────────────────────────────────
    @Test
    fun parsesAllNetworkCodes() {
        val payload = hex(serializedRequestHex)
        val base58 = Base58.encode(payload)
        assertEquals(DashConnectNetwork.MAINNET, DashConnectUri.parseKeyRequest("dash-key:$base58?n=m&v=1").network)
        assertEquals(DashConnectNetwork.TESTNET, DashConnectUri.parseKeyRequest("dash-key:$base58?n=t&v=1").network)
        assertEquals(DashConnectNetwork.DEVNET, DashConnectUri.parseKeyRequest("dash-key:$base58?n=d&v=1").network)
    }

    // ── validation failures ──────────────────────────────────────────────────────
    @Test
    fun rejects_wrongScheme() {
        assertThrows(DashConnectUriException::class.java) {
            DashConnectUri.parseKeyRequest("dash-st:${Base58.encode(hex(serializedRequestHex))}?n=t&v=1")
        }
    }

    @Test
    fun rejects_authorityComponent() {
        assertThrows(DashConnectUriException::class.java) {
            DashConnectUri.parseKeyRequest("dash-key://${Base58.encode(hex(serializedRequestHex))}?n=t&v=1")
        }
    }

    @Test
    fun rejects_missingQuery() {
        assertThrows(DashConnectUriException::class.java) {
            DashConnectUri.parseKeyRequest("dash-key:${Base58.encode(hex(serializedRequestHex))}")
        }
    }

    @Test
    fun rejects_missingVersionOrNetwork() {
        val b = Base58.encode(hex(serializedRequestHex))
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?v=1") }
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?n=t") }
    }

    @Test
    fun rejects_wrongVersion() {
        val b = Base58.encode(hex(serializedRequestHex))
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?n=t&v=2") }
    }

    @Test
    fun rejects_unknownNetwork() {
        val b = Base58.encode(hex(serializedRequestHex))
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?n=x&v=1") }
    }

    @Test
    fun rejects_shortPayload() {
        val short = Base58.encode(ByteArray(10) { 0x01 })
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$short?n=t&v=1") }
    }

    @Test
    fun rejects_wrongPayloadVersionByte() {
        val payload = hex(serializedRequestHex).copyOf()
        payload[0] = 0x02
        val b = Base58.encode(payload)
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?n=t&v=1") }
    }

    @Test
    fun rejects_labelLenOverrun() {
        // Truncate the label but keep the labelLen byte large -> overrun.
        val full = hex(serializedRequestHex)
        val truncated = full.copyOfRange(0, full.size - 5) // drop last 5 label bytes, labelLen still 0x0e
        val b = Base58.encode(truncated)
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?n=t&v=1") }
    }

    @Test
    fun rejects_labelLenTooLarge() {
        val payload = hex(serializedRequestHex).copyOf()
        payload[66] = 0x41 // 65 > 64 max
        val b = Base58.encode(payload)
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:$b?n=t&v=1") }
    }

    @Test
    fun rejects_invalidBase58() {
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest("dash-key:0OIl?n=t&v=1") }
    }

    @Test
    fun emptyLabel_isAccepted() {
        val payload = ByteArray(67)
        payload[0] = 0x01
        System.arraycopy(appPublicKey(), 0, payload, 1, 33)
        // contractId 34..66 left as zeros; labelLen at [66] = 0
        val b = Base58.encode(payload)
        val request = DashConnectUri.parseKeyRequest("dash-key:$b?n=t&v=1")
        assertEquals("", request.label)
    }

    // ── dash-st envelope ──────────────────────────────────────────────────────────
    @Test
    fun parsesDashStEnvelope() {
        val transition = ByteArray(50) { it.toByte() }
        val uri = "dash-st:${Base58.encode(transition)}?n=t&v=1"
        val request = DashConnectUri.parseStRequest(uri)
        assertArrayEquals(transition, request.transitionBytes)
        assertEquals(DashConnectNetwork.TESTNET, request.network)
    }

    @Test
    fun schemeDetectors() {
        assertTrue(DashConnectUri.isKeyUri("dash-key:abc?n=t&v=1"))
        assertTrue(DashConnectUri.isStUri("dash-st:abc?n=t&v=1"))
    }
}
