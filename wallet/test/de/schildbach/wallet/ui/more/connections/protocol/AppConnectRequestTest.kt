package de.schildbach.wallet.ui.more.connections.protocol

import com.google.gson.JsonParser
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Utils
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Published Yappr profile vectors, pinned in app-connect-vectors.json. */
class AppConnectRequestTest {
    private val vector = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader()).asJsonObject
    private val request get() = vector.getAsJsonObject("request")
    private fun bytes(hex: String) = Utils.HEX.decode(hex)
    private fun uri(body: ByteArray) = "dash-key:${Base58.encode(body)}?n=d&v=2"
    private fun body() = bytes(request["bytes"].asString)
    private fun rejects(value: String) {
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseKeyRequest(value) }
    }

    @Test fun `published request and renew vectors decode`() {
        val parsed = DashConnectUri.parseKeyRequest(request["uri"].asString)
        assertEquals(DashConnectNetwork.DEVNET, parsed.network)
        assertEquals("Yappr", parsed.label)
        assertEquals(2, parsed.appConnect!!.auth.size)
        assertEquals(2, parsed.appConnect.auth[0].kind)
        assertEquals(1, parsed.appConnect.auth[0].securityLevel)
        assertEquals(BigInteger("50000000000"), parsed.appConnect.auth[0].totalBudget)
        assertEquals("profile", parsed.appConnect.auth[1].documentType)
        assertEquals(1, parsed.appConnect.encryption.size)
        assertEquals("yap.pr", parsed.appConnect.callback!!.host)
        assertArrayEquals(bytes(request["appEphemeralPubKeyHash"].asString), KeyExchangeCrypto.hash160(parsed.appEphemeralPubKey))
        val renewed = DashConnectUri.parseKeyRequest(vector.getAsJsonObject("renewRequest")["uri"].asString)
        assertTrue(renewed.appConnect!!.renew)
        assertNotNull(renewed.appConnect.identityHint)
    }

    @Test fun `published encrypted payload matches byte for byte`() {
        val inputs = vector.getAsJsonObject("inputs")
        val parsed = DashConnectUri.parseKeyRequest(request["uri"].asString)
        val slots = inputs.getAsJsonArray("slotPrivateKeys").map { bytes(it.asString) }
        val encrypted = KeyExchangeCrypto.encryptAppConnect(slots,
            bytes(inputs["walletEphemeralPrivateKey"].asString), parsed.appEphemeralPubKey,
            Base58.decode(inputs["ownerId"].asString), bytes(inputs["nonce"].asString))
        assertArrayEquals(bytes(vector.getAsJsonObject("response")["encryptedPayload"].asString), encrypted)
        val wrongOwner = Base58.decode(inputs["ownerId"].asString).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(encrypted.contentEquals(KeyExchangeCrypto.encryptAppConnect(slots,
            bytes(inputs["walletEphemeralPrivateKey"].asString), parsed.appEphemeralPubKey, wrongOwner,
            bytes(inputs["nonce"].asString))))
    }

    @Test fun `reject malformed TLVs duplicate params and unsafe callbacks`() {
        val original = request["uri"].asString
        rejects("$original&v=2")
        rejects("$original&%6e=d")
        rejects(uri(body() + byteArrayOf(6, 8) + ByteArray(8)))
        rejects(uri(body().dropLast(1).toByteArray()))
        rejects(uri(body()) + "&cb=http%3A%2F%2Fevil.test")
        rejects(uri(body()) + "&cb=https%3A%2F%2Fuser%40evil.test")
        rejects(uri(body() + byteArrayOf(8, 0)))
        assertNotNull(DashConnectUri.parseKeyRequest(uri(body() + byteArrayOf(7, 0, 7, 0))).appConnect)
    }

    @Test fun `parse errors never retain scanned query values or sensitive parser causes`() {
        val secret = "private-token-sensitive"
        val base = "dash-key:${Base58.encode(body())}"
        val invalidCallback = java.net.URLEncoder.encode("https://example.org/$secret bad", "UTF-8")
        for (input in listOf(
            "$base?n=d&v=$secret",
            "$base?n=$secret&v=1",
            "$base?n=d&v=2&cb=$invalidCallback",
            "$base?n=d&v=2&cb=%$secret",
            "dash-key:$secret?n=d&v=2",
            "dash-st:2?n=d&v=1&cb=%$secret",
            "dash-st:$secret?n=d&v=1"
        )) {
            val error = assertThrows(DashConnectUriException::class.java) {
                if (DashConnectUri.isStUri(input)) DashConnectUri.parseStRequest(input)
                else DashConnectUri.parseKeyRequest(input)
            }
            assertFalse(error.stackTraceToString().contains(secret))
            assertNull(error.cause)
        }
    }

    @Test fun `timestamp freshness permits clock skew but not old requests`() {
        val parsed = DashConnectUri.parseKeyRequest(request["uri"].asString).appConnect!!
        parsed.requireFresh(parsed.createdAt + 600_000)
        parsed.requireFresh(parsed.createdAt - 120_000)
        assertThrows(IllegalArgumentException::class.java) { parsed.requireFresh(parsed.createdAt + 600_001) }
        assertThrows(IllegalArgumentException::class.java) { parsed.requireFresh(parsed.createdAt - 120_001) }
    }

    @Test fun `reject invalid UTF8 control and bidi labels`() {
        for (label in listOf(byteArrayOf(0xc3.toByte()), byteArrayOf(0), "\u202e".toByteArray())) {
            val data = body().copyOfRange(0, 66) + byteArrayOf(label.size.toByte()) + label + body().copyOfRange(72, body().size)
            rejects(uri(data))
        }
    }

    private fun withLabel(label: String): ByteArray {
        val original = body()
        val encoded = label.toByteArray(Charsets.UTF_8)
        val originalLength = original[66].toInt() and 255
        return original.copyOfRange(0, 66) + byteArrayOf(encoded.size.toByte()) + encoded +
            original.copyOfRange(67 + originalLength, original.size)
    }

    @Test fun `reject every Unicode format code point and line separator in app labels`() {
        for (codePoint in 0..Character.MAX_CODE_POINT) {
            if (Character.getType(codePoint) != Character.FORMAT.toInt() && codePoint !in 0x2028..0x2029) continue
            val label = "Yap" + String(Character.toChars(codePoint)) + "pr"
            assertThrows("Accepted U+${codePoint.toString(16)}", DashConnectUriException::class.java) {
                DashConnectUri.parseKeyRequest(uri(withLabel(label)))
            }
        }
    }

    @Test fun `ordinary international and supplementary app labels remain valid`() {
        for (label in listOf("Yappr", "Caf\u00e9", "\u65e5\u672c\u8a9e", "Wallet \ud83d\udcb3")) {
            assertEquals(label, DashConnectUri.parseKeyRequest(uri(withLabel(label))).label)
        }
    }

    private fun tlv(type: Int, value: ByteArray) = byteArrayOf(type.toByte(), value.size.toByte()) + value
    private fun prefix() = body().copyOfRange(0, 72)
    private fun grant(kind: Int = 0, level: Int = 2, budget: ByteArray = ByteArray(8), suffix: ByteArray = byteArrayOf()) =
        byteArrayOf(kind.toByte(), level.toByte()) + ByteArray(32) { 9 } + budget + ByteArray(8) + suffix
    private fun created() = tlv(6, java.nio.ByteBuffer.allocate(8).putLong(1_791_676_800_000L).array())

    @Test fun `reject group without budget disallowed levels and repeated bounds`() {
        rejects(uri(prefix() + tlv(2, grant(2, 1)) + created()))
        rejects(uri(prefix() + tlv(2, grant(0, 1)) + created()))
        rejects(uri(prefix() + tlv(2, grant(0, 0)) + created()))
        rejects(uri(prefix() + tlv(2, grant(3)) + created()))
        rejects(uri(prefix() + tlv(2, grant()) + tlv(2, grant()) + created()))
        rejects(uri(prefix() + tlv(2, grant(1, suffix = byteArrayOf(0))) + created()))
        rejects(uri(prefix() + tlv(2, grant()) + created() + tlv(3, ByteArray(16))))
        rejects(uri(prefix() + tlv(2, grant())))
    }

    @Test fun `unsigned budget is not truncated and unsafe timestamps fail`() {
        val parsed = DashConnectUri.parseKeyRequest(uri(prefix() + tlv(2, grant(budget = ByteArray(8) { -1 })) + created()))
        assertEquals(BigInteger("18446744073709551615"), parsed.appConnect!!.auth.single().totalBudget)
        rejects(uri(prefix() + tlv(2, grant()) + tlv(6, ByteArray(8) { -1 })))
        rejects(uri(prefix() + tlv(2, grant()) + tlv(6, ByteArray(8))))
    }
}
