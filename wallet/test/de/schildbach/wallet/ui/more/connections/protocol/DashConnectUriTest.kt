/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections.protocol

import com.google.gson.JsonParser
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Utils
import org.junit.Assert.*
import org.junit.Test

class DashConnectUriTest {
    private val fixture = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader())
        .asJsonObject.getAsJsonObject("request")
    private fun payload() = Utils.HEX.decode(fixture["bytes"].asString)
    private fun uri(bytes: ByteArray = payload(), network: String = "d", version: String = "2") =
        "dash-key:${Base58.encode(bytes)}?n=$network&v=$version"

    @Test fun publishedV2FixtureParses() {
        val request = DashConnectUri.parseKeyRequest(fixture["uri"].asString)
        assertEquals("Yappr", request.label)
        assertEquals(DashConnectNetwork.DEVNET, request.network)
        assertEquals(33, request.appEphemeralPubKey.size)
        assertEquals(32, request.contractId.size)
        assertTrue(KeyExchangeCrypto.isValidCompressedPoint(request.appEphemeralPubKey))
        assertArrayEquals(payload(), request.requireAppConnect().bytes)
    }

    @Test fun v2ParsesAllNetworkCodes() {
        DashConnectNetwork.entries.forEach { network ->
            assertEquals(network, DashConnectUri.parseKeyRequest(uri(network = network.code)).network)
        }
    }

    @Test fun legacyYapprLoginUriIsExplicitlyRejected() {
        val legacy = "dash-key:3pYF4Tv365PGzvLukfiqx44ZinzxvhwjF2nmwgxDu7GXQpsoH7V9VLi5u8fWKMCxVnuERp4" +
            "7QVLoceh4KvPU9a1Xs7PgAGMjeJmcGHyHQY7yry?n=t&v=1"
        val error = assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(legacy) }
        assertEquals(DashConnectUri.LEGACY_KEY_REQUEST_ERROR, error.message)
    }

    @Test fun v1IsRejectedForEveryNetworkEvenWithV2Bytes() {
        DashConnectNetwork.entries.forEach { network ->
            val error = assertThrows(DashConnectUriException::class.java) {
                DashConnectUri.parseKeyRequest(uri(network = network.code, version = "1"))
            }
            assertEquals(DashConnectUri.LEGACY_KEY_REQUEST_ERROR, error.message)
        }
    }

    @Test fun legacyPayloadCannotBeRelabeledAsV2() {
        val bytes = payload().copyOfRange(0, 72).also { it[0] = 1 }
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(uri(bytes)) }
    }

    @Test fun rejectsUnsupportedVersionsAndDuplicateVersionDowngrades() {
        for (version in listOf("0", "01", "02", "3", "", "-1")) {
            assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(uri(version = version)) }
        }
        for (input in listOf(uri() + "&v=1", uri(version = "1") + "&v=2", uri() + "&%76=1")) {
            assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(input) }
        }
    }

    @Test fun rejectsMalformedEnvelopeNetworkOrBase58() {
        val body = Base58.encode(payload())
        for (input in listOf(
            "dash-st:$body?n=d&v=2", "dash-key://$body?n=d&v=2", "dash-key:$body", "dash-key:$body?v=2",
            "dash-key:$body?n=d", uri(network = "x"), "dash-key:?n=d&v=2", "dash-key:0OIl?n=d&v=2",
            uri() + "#fragment", "dash-key:${"1".repeat(4096)}?n=d&v=2"
        )) {
            assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(input) }
        }
    }

    @Test fun rejectsMalformedV2Payloads() {
        val original = payload()
        for (bytes in listOf(
            ByteArray(10) { 2 }, original.copyOf().also { it[0] = 3 },
            original.copyOf().also { it[66] = 65 }, original.copyOf().also { it[1] = 0 }, original.copyOfRange(0, 68)
        )) {
            assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(uri(bytes)) }
        }
    }

    @Test fun legacyLabelsAreNeverDecodedOrExposed() {
        val label = "private-label\n\u202e"
        val legacyBytes = payload().copyOfRange(0, 66).also { it[0] = 1 } +
            byteArrayOf(label.toByteArray().size.toByte()) + label.toByteArray()
        val error = assertThrows(DashConnectUriException::class.java) {
            DashConnectUri.parseKeyRequest(uri(legacyBytes, version = "1"))
        }
        assertEquals(DashConnectUri.LEGACY_KEY_REQUEST_ERROR, error.message)
        assertFalse(error.stackTraceToString().contains(label))
        assertNull(error.cause)
    }

    @Test fun schemeDetectionDoesNotClaimVersionSupport() {
        assertTrue(DashConnectUri.isKeyUri("dash-key:abc?n=t&v=1"))
        assertTrue(DashConnectUri.isKeyUri(uri()))
        assertTrue(DashConnectUri.isConnectUri("dash-st:2?n=d&v=1"))
    }
}
