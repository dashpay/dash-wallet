/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections.protocol

import com.google.gson.JsonParser
import org.bitcoinj.core.Utils
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** `dashpay://connect` carrier of the App Connect DIP (dashpay/dips#181), "Request Vector". */
class DashPayConnectUriTest {
    private val fixture = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader())
        .asJsonObject.getAsJsonObject("request")
    private val body: ByteArray = Utils.HEX.decode(fixture["bytes"].asString)
    private val callback = "https://yap.pr/connect?r=d77dc0da9df83a47755a15c42c5d9db9d7a7fd62"

    /** The devnet `dashpay://connect` URI printed in the DIP's "Request Vector" section. */
    private val dipUri = "dashpay://connect?v=2&n=d&r=AgPoPLkn6F-WrlCpXKO-Icx6N1UTmpQbFdmbAa9P7PDScPiDPHotWKuBbCSu" +
        "kV_iqpRnZYi0Z7oenyskwaPff9t4BVlhcHByAjICAbBZDlkmnGiFrPcdJ9386FGH4syXNsQulYEnzTW-bTjoAAAAC6Q7dAAAAAGi9760AAI6" +
        "AQKiobSsb-8i6ioaaOgSNkSzV4dfa0EsGBCSgcFG57JxvAAAAAA7msoAAAABove-tAAHcHJvZmlsZQMQppsbT2ZmgUU97Dc0ZnpasgQh-IM" +
        "8ei1Yq4FsJK6RX-KqlGdliLRnuh6fKyTBo99_23gBBggAAAGhKEJcAA&cb=https%3A%2F%2Fyap.pr%2Fconnect%3Fr%3Dd77dc0da9df83" +
        "a47755a15c42c5d9db9d7a7fd62"

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val r get() = encode(body)
    private fun uri(r: String = this.r, query: String = "v=2&n=d&r=$r") = "dashpay://connect?$query"

    private fun rejects(value: String, message: String? = null) {
        val error = assertThrows(value.take(100), DashConnectUriException::class.java) {
            DashConnectUri.parseConnectRequest(value)
        }
        if (message != null) assertEquals(message, error.message)
    }

    @Test fun `DIP devnet vector decodes to the vector body bytes and callback`() {
        val parsed = DashConnectUri.parseConnectRequest(dipUri)
        assertArrayEquals(body, parsed.requireAppConnect().bytes)
        assertEquals(callback, parsed.requireAppConnect().callback.toString())
        assertEquals(DashConnectNetwork.DEVNET, parsed.network)
        assertEquals("Yappr", parsed.label)
        assertTrue(parsed.requireAppConnect().auth.isNotEmpty())
    }

    @Test fun `DIP vector r equals canonical encoding of the vector bytes`() {
        assertTrue(dipUri.contains("r=$r&cb="))
    }

    @Test fun `both carriers decode the same request`() {
        val viaKey = DashConnectUri.parseKeyRequest(fixture["uri"].asString)
        val viaDashPay = DashConnectUri.parseConnectRequest(dipUri)
        assertEquals(viaKey, viaDashPay)
        assertEquals(viaKey, DashConnectUri.parseRequest(fixture["uri"].asString))
        assertEquals(viaKey, DashConnectUri.parseRequest(dipUri))
    }

    @Test fun `scheme and host are case insensitive`() {
        listOf("DASHPAY://CONNECT?", "DashPay://Connect?", "dashpay://CONNECT?").forEach { prefix ->
            val value = prefix + dipUri.substringAfter("?")
            assertTrue(value, DashConnectUri.isConnectUri(value))
            assertTrue(value, DashConnectUri.isDashPayConnectUri(value))
            assertArrayEquals(body, DashConnectUri.parseConnectRequest(value).requireAppConnect().bytes)
        }
    }

    @Test fun `single trailing slash and any parameter order are accepted`() {
        val slash = dipUri.replace("connect?", "connect/?")
        assertArrayEquals(body, DashConnectUri.parseConnectRequest(slash).requireAppConnect().bytes)
        val reordered = uri(query = "cb=${java.net.URLEncoder.encode(callback, "UTF-8")}&r=$r&n=d&v=2")
        assertEquals(callback, DashConnectUri.parseConnectRequest(reordered).requireAppConnect().callback.toString())
    }

    @Test fun `unknown parameters are ignored and cb is optional`() {
        assertNull(DashConnectUri.parseConnectRequest(uri()).requireAppConnect().callback)
        assertNotNull(DashConnectUri.parseConnectRequest(uri(query = "v=2&n=d&r=$r&foo=1&foo=2&x")))
    }

    @Test fun `all three network codes map`() {
        DashConnectNetwork.entries.forEach {
            assertEquals(it, DashConnectUri.parseConnectRequest(uri(query = "v=2&n=${it.code}&r=$r")).network)
        }
    }

    @Test fun `version must be exactly 2`() {
        listOf("1", "3", "02", "", "2 ").forEach {
            rejects(uri(query = "v=$it&n=d&r=$r"), "Unsupported connection version")
        }
        rejects(uri(query = "n=d&r=$r"), "Unsupported connection version")
    }

    @Test fun `network must be a known code`() {
        listOf("x", "", "M", "mt").forEach { rejects(uri(query = "v=2&n=$it&r=$r")) }
        rejects(uri(query = "v=2&r=$r"))
    }

    @Test fun `repeated known parameters are invalid`() {
        rejects(uri(query = "v=2&n=d&r=$r&v=2"))
        rejects(uri(query = "v=2&n=d&n=d&r=$r"))
        rejects(uri(query = "v=2&n=d&r=$r&r=$r"))
        rejects(uri(query = "v=2&n=d&r=$r&%72=$r"))
        rejects(dipUri + "&cb=https%3A%2F%2Fyap.pr%2F")
    }

    @Test fun `r is required`() {
        rejects(uri(query = "v=2&n=d"))
        rejects(uri(query = "v=2&n=d&r="))
        rejects("dashpay://connect")
        rejects("dashpay://connect?")
    }

    @Test fun `r refuses padding and the standard alphabet`() {
        val padded = Base64.getUrlEncoder().encodeToString(body)
        assertTrue(padded.endsWith("="))
        rejects(uri(r = padded))
        rejects(uri(r = r.replaceFirst('-', '+')))
        rejects(uri(r = r.replaceFirst('_', '/')))
        rejects(uri(r = r.replaceFirst('-', '/')))
        rejects(uri(r = r.dropLast(1) + "%2F"))
        rejects(uri(r = r.dropLast(1) + "%2B"))
        rejects(uri(r = r.dropLast(1) + "%3D"))
        rejects(uri(query = "v=2&n=d&r=${r.dropLast(1)}+"))
        rejects(uri(r = r.dropLast(1) + "!"))
        rejects(uri(r = r.dropLast(1) + "é"))
        rejects(uri(r = r.dropLast(2) + " A"))
    }

    @Test fun `r refuses a length of 1 mod 4`() {
        assertEquals(2, r.length % 4)
        assertEquals(1, (r + "AAA").length % 4)
        assertEquals(1, r.dropLast(1).length % 4)
        rejects(uri(r = r + "AAA"))
        rejects(uri(r = r.dropLast(1)))
    }

    @Test fun `r refuses non zero trailing bits`() {
        assertEquals(2, r.length % 4)
        assertTrue(r.endsWith("A"))
        // the final character carries four padding bits, which must all be zero
        rejects(uri(r = r.dropLast(1) + "B"))
        rejects(uri(r = r.dropLast(1) + "_"))
        assertNotNull(DashConnectUri.parseConnectRequest(uri(r = r)))
    }

    @Test fun `r and the decoded body are bounded`() {
        val longR = "A".repeat(1367)
        rejects(uri(r = longR))
        rejects(uri(r = encode(ByteArray(1025))))
        // a body that fits the bounds but is not a request is still invalid
        rejects(uri(r = encode(ByteArray(1024))))
        rejects(uri(r = encode(ByteArray(10))))
    }

    @Test fun `whole uri over 4096 bytes is refused before decoding`() {
        val padding = "&pad=" + "a".repeat(4096)
        assertTrue((uri() + padding).length > 4096)
        rejects(uri() + padding, "Connection QR is too large")
        // 4,096 bytes of UTF-8, not characters
        val filler = "&pad=" + "é".repeat((4096 - uri().length) / 2)
        assertTrue(filler.length + uri().length <= 4096)
        assertTrue((uri() + filler).toByteArray().size > 4096)
        rejects(uri() + filler, "Connection QR is too large")
        // exactly 4,096 bytes is still accepted
        val exact = uri() + "&pad=" + "a".repeat(4096 - uri().length - 5)
        assertEquals(4096, exact.toByteArray().size)
        assertNotNull(DashConnectUri.parseConnectRequest(exact))
    }

    @Test fun `shape rules reject fragment userinfo port and extra path`() {
        val q = "v=2&n=d&r=$r"
        rejects("dashpay://connect?$q#frag")
        rejects("dashpay://connect#frag?$q")
        rejects("dashpay://user@connect?$q")
        rejects("dashpay://user:pw@connect?$q")
        rejects("dashpay://connect:8080?$q")
        rejects("dashpay://connect/extra?$q")
        rejects("dashpay://connect//?$q")
        rejects("dashpay://connect/x/?$q")
        rejects("dashpay:///connect?$q", "Invalid connection link")
        rejects("dashpay:connect?$q")
        rejects("dashpay://connect@evil.test?$q")
        rejects("dashpay://connect.evil.test?$q", DashConnectUri.NEEDS_NEWER_WALLET_ERROR)
    }

    @Test fun `callback keeps the dash-key rules`() {
        rejects(uri(query = "v=2&n=d&r=$r&cb=http%3A%2F%2Fyap.pr"))
        rejects(uri(query = "v=2&n=d&r=$r&cb=https%3A%2F%2Fuser%40yap.pr"))
        rejects(uri(query = "v=2&n=d&r=$r&cb=javascript%3Aalert(1)"))
        rejects(uri(query = "v=2&n=d&r=$r&cb="))
        rejects(uri(query = "v=2&n=d&r=$r&cb=" + java.net.URLEncoder.encode("https://yap.pr/" + "a".repeat(512), "UTF-8")))
    }

    @Test fun `invite is not a connect link`() {
        val invite = "dashpay://invite?du=alice&assetlocktx=a0963d1b&pk=cNihCPEQ&islock=0101b1"
        assertFalse(DashConnectUri.isConnectUri(invite))
        assertFalse(DashConnectUri.isConnectUri(invite.uppercase()))
        assertFalse(DashConnectUri.isDashPayConnectUri(invite))
        assertFalse(DashConnectUri.isDashPayVerbUri(invite))
        assertFalse(DashConnectUri.isConnectUri("dashpay://invite"))
        rejects(invite, "Invalid connection link")
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseRequest(invite) }
    }

    @Test fun `unknown verbs say the link needs a newer wallet`() {
        listOf("dashpay://sign?v=2&n=d&exp=1&st=AAAA", "DASHPAY://SIGN?v=2", "dashpay://frobnicate", "dashpay://connectx?v=2&n=d&r=$r")
            .forEach { value ->
                assertTrue(value, DashConnectUri.isConnectUri(value))
                assertFalse(value, DashConnectUri.isDashPayConnectUri(value))
                rejects(value, DashConnectUri.NEEDS_NEWER_WALLET_ERROR)
                val error = assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseRequest(value) }
                assertEquals(DashConnectUri.NEEDS_NEWER_WALLET_ERROR, error.message)
            }
        // an oversized sign link (valid at 28 KiB from the OS) is still reported as needing a newer wallet
        rejects("dashpay://sign?st=" + "A".repeat(20000), DashConnectUri.NEEDS_NEWER_WALLET_ERROR)
    }

    @Test fun `scheme fold is ASCII only`() {
        val lookalike = "da\u017fhpay://connect?" + dipUri.substringAfter("?")
        assertFalse(DashConnectUri.isConnectUri(lookalike))
        assertFalse(DashConnectUri.isDashPayConnectUri(lookalike))
    }

    @Test fun `non dashpay uris are not connect carriers`() {
        listOf("https://example.org", "dash:XyAddress", "dashpay:", "dashpay://", "dashpay:connect?v=2", "dashwallet://connect?v=2")
            .forEach {
                assertFalse(it, DashConnectUri.isConnectUri(it))
                assertFalse(it, DashConnectUri.isDashPayConnectUri(it))
            }
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseRequest("dash-st:2?n=d&v=1") }
        assertThrows(DashConnectUriException::class.java) { DashConnectUri.parseRequest("https://example.org") }
    }

    @Test fun `existing carriers still route and dash-key ignores r`() {
        assertTrue(DashConnectUri.isConnectUri(fixture["uri"].asString))
        assertTrue(DashConnectUri.isConnectUri("dash-st:2?n=d&v=1"))
        assertFalse(DashConnectUri.isConnectUri("DASH-KEY:abc?n=d&v=2"))
        // r is not a known dash-key parameter, so repeating it there is still ignored
        assertNotNull(DashConnectUri.parseKeyRequest(fixture["uri"].asString + "&r=1&r=2"))
    }

    @Test fun `base64url decoder is strict and round trips`() {
        val random = Random(7)
        for (length in 1..64) {
            val bytes = random.nextBytes(length)
            assertArrayEquals(bytes, DashConnectUri.decodeBase64Url(encode(bytes), 1366, 1024))
        }
        assertArrayEquals(ByteArray(1024) { it.toByte() },
            DashConnectUri.decodeBase64Url(encode(ByteArray(1024) { it.toByte() }), 1366, 1024).also {
                assertEquals(1366, encode(it).length)
            })
        assertArrayEquals(byteArrayOf(0), DashConnectUri.decodeBase64Url("AA", 10, 10))
        assertArrayEquals(byteArrayOf(0, 0), DashConnectUri.decodeBase64Url("AAA", 10, 10))
        assertArrayEquals(byteArrayOf(-1), DashConnectUri.decodeBase64Url("_w", 10, 10))
        listOf("", "A", "AAAAA", "AB", "AAB", "AAC", "_x", "AA==", "AA=", "A+A_", "A/A_", "AAA ", "AAAé")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { DashConnectUri.decodeBase64Url(it, 10, 10) } }
        assertThrows(IllegalArgumentException::class.java) { DashConnectUri.decodeBase64Url("AAAAAAAA", 7, 10) }
        assertThrows(IllegalArgumentException::class.java) { DashConnectUri.decodeBase64Url("AAAAAAAA", 10, 5) }
    }
}
