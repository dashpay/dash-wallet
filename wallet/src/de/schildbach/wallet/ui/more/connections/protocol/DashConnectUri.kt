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
import java.util.Locale

/**
 * Network identifiers used in the `?n=` query parameter of DashConnect URIs, matching the
 * DApp's `YAPPR_NETWORK_IDS` map (`m`=mainnet, `t`=testnet, `d`=devnet).
 */
enum class DashConnectNetwork(val code: String) {
    MAINNET("m"),
    TESTNET("t"),
    DEVNET("d");

    companion object {
        fun fromCode(code: String?): DashConnectNetwork? = entries.firstOrNull { it.code == code }
    }
}

/** Thrown when a scanned QR is not a well-formed DashConnect URI, or fails validation. */
class DashConnectUriException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DashStRequest(val transitionBytes: ByteArray, val network: DashConnectNetwork)

/**
 * A parsed `dash-key:` login request. Operational entry points require the v2 [appConnect] payload.
 * The [label] is
 * UNAUTHENTICATED — it is a claim by the app about itself and MUST be treated as
 * spoofable in the UI.
 *
 * V2 payload decoding and validation are handled by [AppConnectParser].
 */
data class DashKeyRequest(
    val appEphemeralPubKey: ByteArray,
    val contractId: ByteArray,
    val label: String,
    val network: DashConnectNetwork,
    val appConnect: AppConnectRequest? = null
) {
    fun requireAppConnect(): AppConnectRequest = appConnect
        ?: throw DashConnectUriException(DashConnectUri.LEGACY_KEY_REQUEST_ERROR)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DashKeyRequest) return false
        return appEphemeralPubKey.contentEquals(other.appEphemeralPubKey) &&
            contractId.contentEquals(other.contractId) &&
            label == other.label &&
            network == other.network &&
            ((appConnect == null && other.appConnect == null) || (appConnect != null && other.appConnect != null &&
                appConnect.bytes.contentEquals(other.appConnect.bytes) && appConnect.callback == other.appConnect.callback))
    }

    override fun hashCode(): Int {
        var result = appEphemeralPubKey.contentHashCode()
        result = 31 * result + contractId.contentHashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + network.hashCode()
        result = 31 * result + (appConnect?.bytes?.contentHashCode() ?: 0)
        result = 31 * result + (appConnect?.callback?.hashCode() ?: 0)
        return result
    }
}

/**
 * Parser for the DashConnect login URI scheme. Pure and Android-free.
 *
 * Rules enforced:
 *  - scheme literal followed by `:` and NO `//` authority component
 *  - the `?` query separator must be present
 *  - query params `n` and `v` are both required; `v` must equal `2` for login
 *  - `n` must decode to a known network; callers additionally gate on the wallet's active network
 *  - the body between `:` and `?` is plain Base58 (NO checksum)
 */
object DashConnectUri {

    const val KEY_SCHEME = "dash-key:"
    const val ST_SCHEME = "dash-st:"

    const val DASHPAY_PREFIX = "dashpay://"
    const val CONNECT_VERB = "connect"
    const val INVITE_VERB = "invite"
    const val NEEDS_NEWER_WALLET_ERROR = "This link needs a newer version of the wallet"
    private const val MAX_CONNECT_URI_BYTES = 4096
    private const val MAX_CONNECT_R_CHARS = 1366
    private const val MAX_CONNECT_R_BYTES = 1024
    private val CONNECT_SINGLE_VALUED_PARAMS = setOf("v", "n", "r", "cb")

    const val VERSION = 2
    const val LEGACY_KEY_REQUEST_ERROR = "Unsupported legacy connection request. Generate a new QR code."

    /**
     * Parses and validates a `dash-key:` login-request URI. Returns a [DashKeyRequest] with the
     * validated compressed ephemeral public key (guaranteed on-curve), 32-byte contract id, and
     * the (unauthenticated) label.
     *
     * @throws DashConnectUriException on any malformed input or validation failure.
     */
    fun parseKeyRequest(uri: String): DashKeyRequest {
        if (uri.length > 4096) throw DashConnectUriException("Connection QR is too large")
        if (!isKeyUri(uri) || uri.startsWith("dash-key://") || '#' in uri || '?' !in uri)
            throw DashConnectUriException("Invalid connection link")
        try {
            val query = AppConnectParser.query(uri.substringAfter('?'))
            if (query["v"] == "1") throw DashConnectUriException(LEGACY_KEY_REQUEST_ERROR)
            if (query["v"] != VERSION.toString()) throw DashConnectUriException("Unsupported connection version")
            val network = DashConnectNetwork.fromCode(query["n"])
                ?: throw DashConnectUriException("Unknown network")
            return AppConnectParser.parse(decodeBase58(uri.substringAfter(KEY_SCHEME).substringBefore('?')),
                network, query["cb"])
        } catch (e: DashConnectUriException) { throw e }
        catch (e: Exception) { throw DashConnectUriException("Invalid connection request") }
    }

    /**
     * Parses a `dashpay://connect` request (App Connect DIP, "connect URI"). The body after `r=` is
     * the same request v2 body as the `dash-key:` carrier and goes through the same
     * [AppConnectParser]; only the wrapper differs.
     *
     * Rules: scheme and host compared case-insensitively; path empty or a single `/`; no userinfo,
     * port or fragment; query split on `&` and form-decoded; a repeated `v`, `n`, `r` or `cb` is
     * invalid; unknown parameters are ignored; `v` is exactly `2`; `r` is REQUIRED canonical
     * base64url (at most 1,366 characters, 1,024 bytes); the whole URI is at most 4,096 bytes,
     * checked before anything is decoded. `cb` keeps the `dash-key:` rules, enforced by
     * [AppConnectParser.parse]. The caller still compares [DashKeyRequest.network] with the
     * wallet's network before doing any network read.
     *
     * A `dashpay://` URI whose verb this wallet does not implement (for example `sign`) fails with
     * [NEEDS_NEWER_WALLET_ERROR].
     *
     * @throws DashConnectUriException on any malformed input or validation failure.
     */
    fun parseConnectRequest(uri: String): DashKeyRequest {
        val verb = dashPayVerb(uri)
        if (verb.isNullOrEmpty() || verb == INVITE_VERB) throw DashConnectUriException("Invalid connection link")
        if (verb != CONNECT_VERB) throw DashConnectUriException(NEEDS_NEWER_WALLET_ERROR)
        if (uri.toByteArray(Charsets.UTF_8).size > MAX_CONNECT_URI_BYTES) {
            throw DashConnectUriException("Connection QR is too large")
        }
        try {
            val rest = uri.substring(DASHPAY_PREFIX.length)
            val queryStart = rest.indexOf('?')
            if ('#' in rest || queryStart < 0) throw DashConnectUriException("Invalid connection link")
            val authorityAndPath = rest.substring(0, queryStart)
            val pathStart = authorityAndPath.indexOf('/')
            val host = if (pathStart < 0) authorityAndPath else authorityAndPath.substring(0, pathStart)
            val path = if (pathStart < 0) "" else authorityAndPath.substring(pathStart)
            // userinfo and port both put '@' or ':' in the host part, which then no longer equals "connect"
            if (!host.equals(CONNECT_VERB, ignoreCase = true) || (path.isNotEmpty() && path != "/")) {
                throw DashConnectUriException("Invalid connection link")
            }
            val query = AppConnectParser.query(rest.substring(queryStart + 1), CONNECT_SINGLE_VALUED_PARAMS)
            if (query["v"] != VERSION.toString()) throw DashConnectUriException("Unsupported connection version")
            val network = DashConnectNetwork.fromCode(query["n"])
                ?: throw DashConnectUriException("Unknown network")
            val body = decodeBase64Url(
                query["r"] ?: throw DashConnectUriException("Missing connection request"),
                MAX_CONNECT_R_CHARS, MAX_CONNECT_R_BYTES
            )
            return AppConnectParser.parse(body, network, query["cb"])
        } catch (e: DashConnectUriException) { throw e }
        catch (e: Exception) { throw DashConnectUriException("Invalid connection request", e) }
    }

    /**
     * Parses a request from either carrier: `dash-key:` or `dashpay://connect`. `dash-st:` is not a
     * login request and is refused here, as before.
     */
    fun parseRequest(uri: String): DashKeyRequest = when {
        isKeyUri(uri) -> parseKeyRequest(uri)
        isDashPayVerbUri(uri) -> parseConnectRequest(uri)
        else -> throw DashConnectUriException("Not a DashConnect QR code")
    }

    /** True if [uri] uses the `dash-key:` scheme. */
    fun isKeyUri(uri: String): Boolean = uri.startsWith(KEY_SCHEME)

    fun isStUri(uri: String): Boolean = uri.startsWith(ST_SCHEME)

    /** True for `dashpay://connect...`, comparing scheme and host case-insensitively. */
    fun isDashPayConnectUri(uri: String): Boolean = dashPayVerb(uri) == CONNECT_VERB

    /**
     * True for any `dashpay://<verb>` URI other than `invite`, which stays with the invitation
     * handler. A verb this wallet does not implement is reported as needing a newer wallet.
     */
    fun isDashPayVerbUri(uri: String): Boolean = dashPayVerb(uri)?.let { it.isNotEmpty() && it != INVITE_VERB } == true

    fun isConnectUri(uri: String): Boolean = isKeyUri(uri) || isStUri(uri) || isDashPayVerbUri(uri)

    /** The lowercased verb (host) of a `dashpay://` URI, or null when [uri] is not one. */
    private fun dashPayVerb(uri: String): String? {
        // ASCII-only fold: startsWith(ignoreCase) would also accept look-alikes such as U+017F for 's'
        if (uri.length < DASHPAY_PREFIX.length ||
            uri.substring(0, DASHPAY_PREFIX.length).lowercase(Locale.ROOT) != DASHPAY_PREFIX) return null
        val end = uri.indexOfAny(charArrayOf('/', '?', '#', '@', ':'), DASHPAY_PREFIX.length)
        return uri.substring(DASHPAY_PREFIX.length, if (end < 0) uri.length else end).lowercase(Locale.ROOT)
    }

    /**
     * Strict RFC 4648 section 5 base64url without padding. Refuses `=`, `+`, `/` and anything else
     * outside the alphabet, a length of 1 mod 4, and non-zero trailing bits, so every value has
     * exactly one encoding. [java.util.Base64] is lenient about padding and trailing bits, so this
     * decodes by hand.
     */
    internal fun decodeBase64Url(value: String, maxChars: Int, maxBytes: Int): ByteArray {
        require(value.isNotEmpty() && value.length <= maxChars) { "Invalid base64url length" }
        require(value.length % 4 != 1) { "Invalid base64url length" }
        val out = ByteArray(value.length * 6 / 8)
        var accumulator = 0
        var bits = 0
        var written = 0
        for (c in value) {
            val sextet = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> throw IllegalArgumentException("Invalid base64url character")
            }
            accumulator = (accumulator shl 6) or sextet
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = (accumulator shr bits).toByte()
                accumulator = accumulator and ((1 shl bits) - 1)
            }
        }
        require(accumulator == 0) { "Non-canonical base64url trailing bits" }
        require(written <= maxBytes) { "Decoded value too large" }
        return out
    }

    fun parseStRequest(uri: String): DashStRequest {
        if (uri.length > 6144 || !isStUri(uri) || uri.startsWith("dash-st://") || '#' in uri)
            throw DashConnectUriException("Invalid or oversized dash-st link")
        val body = uri.substringAfter(ST_SCHEME).substringBefore('?')
        if ('?' !in uri || body.isEmpty() || body.length > 5594)
            throw DashConnectUriException("Invalid dash-st payload")
        val params = try { AppConnectParser.query(uri.substringAfter('?')) }
        catch (error: Exception) { throw DashConnectUriException("Invalid dash-st query") }
        if (params["v"] != "1") throw DashConnectUriException("Unsupported dash-st version")
        val network = DashConnectNetwork.fromCode(params["n"])
            ?: throw DashConnectUriException("Unknown dash-st network")
        val bytes = decodeBase58(body)
        if (bytes.isEmpty() || bytes.size > 4096) throw DashConnectUriException("Invalid dash-st payload size")
        return DashStRequest(bytes, network)
    }

    private fun decodeBase58(body: String): ByteArray = try {
        Base58.decode(body) // plain Base58, no checksum
    } catch (ex: Exception) {
        throw DashConnectUriException("Invalid Base58 payload")
    }
}
