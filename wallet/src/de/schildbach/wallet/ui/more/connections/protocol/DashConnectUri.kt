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

    /** True if [uri] uses the `dash-key:` scheme. */
    fun isKeyUri(uri: String): Boolean = uri.startsWith(KEY_SCHEME)

    fun isStUri(uri: String): Boolean = uri.startsWith(ST_SCHEME)

    fun isConnectUri(uri: String): Boolean = isKeyUri(uri) || isStUri(uri)

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
