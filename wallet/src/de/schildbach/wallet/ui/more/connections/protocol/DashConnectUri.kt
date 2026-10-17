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

/**
 * A parsed and fully validated `dash-key:` login request (QR #1). The [label] is
 * UNAUTHENTICATED — it is a claim by the app about itself and MUST be treated as
 * spoofable in the UI.
 *
 * Payload layout (after plain Base58 decode, min 67 bytes):
 * `version(1B=0x01) || appEphemeralPubKey(33B) || contractId(32B) || labelLen(1B, 0..64) || label(UTF-8)`
 */
data class DashKeyRequest(
    val appEphemeralPubKey: ByteArray,
    val contractId: ByteArray,
    val label: String,
    val network: DashConnectNetwork
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DashKeyRequest) return false
        return appEphemeralPubKey.contentEquals(other.appEphemeralPubKey) &&
            contractId.contentEquals(other.contractId) &&
            label == other.label &&
            network == other.network
    }

    override fun hashCode(): Int {
        var result = appEphemeralPubKey.contentHashCode()
        result = 31 * result + contractId.contentHashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + network.hashCode()
        return result
    }
}

/**
 * Parser for the DashConnect login URI scheme. Pure and Android-free.
 *
 * Rules enforced:
 *  - scheme literal followed by `:` and NO `//` authority component
 *  - the `?` query separator must be present
 *  - query params `n` and `v` are both required; `v` must equal `1`
 *  - `n` must decode to a known network; callers additionally gate on the wallet's active network
 *  - the body between `:` and `?` is plain Base58 (NO checksum)
 */
object DashConnectUri {

    const val KEY_SCHEME = "dash-key:"

    const val VERSION = 1
    const val PAYLOAD_VERSION_BYTE: Byte = 0x01

    const val MIN_KEY_PAYLOAD_LENGTH =
        1 + KeyExchangeCrypto.COMPRESSED_PUBKEY_LENGTH + 32 + 1 // 67
    const val MAX_LABEL_LENGTH = 64

    /**
     * Parses and validates a `dash-key:` login-request URI. Returns a [DashKeyRequest] with the
     * validated compressed ephemeral public key (guaranteed on-curve), 32-byte contract id, and
     * the (unauthenticated) label.
     *
     * @throws DashConnectUriException on any malformed input or validation failure.
     */
    fun parseKeyRequest(uri: String): DashKeyRequest {
        val (body, network) = parseEnvelope(uri, KEY_SCHEME)
        val payload = decodeBase58(body)

        if (payload.size < MIN_KEY_PAYLOAD_LENGTH) {
            throw DashConnectUriException(
                "dash-key payload too short: ${payload.size} < $MIN_KEY_PAYLOAD_LENGTH"
            )
        }
        var offset = 0
        val version = payload[offset]; offset += 1
        if (version != PAYLOAD_VERSION_BYTE) {
            throw DashConnectUriException("unsupported dash-key payload version: $version")
        }
        val appEphemeralPubKey = payload.copyOfRange(offset, offset + KeyExchangeCrypto.COMPRESSED_PUBKEY_LENGTH)
        offset += KeyExchangeCrypto.COMPRESSED_PUBKEY_LENGTH
        val contractId = payload.copyOfRange(offset, offset + 32)
        offset += 32
        val labelLen = payload[offset].toInt() and 0xff; offset += 1

        if (labelLen > MAX_LABEL_LENGTH) {
            throw DashConnectUriException("label length $labelLen exceeds max $MAX_LABEL_LENGTH")
        }
        if (offset + labelLen > payload.size) {
            throw DashConnectUriException("label length $labelLen overruns payload")
        }
        val label = String(payload.copyOfRange(offset, offset + labelLen), Charsets.UTF_8)

        // Reject an ephemeral pubkey that is not a valid secp256k1 point before it ever
        // reaches ECDH. The QR is untrusted input.
        if (!KeyExchangeCrypto.isValidCompressedPoint(appEphemeralPubKey)) {
            throw DashConnectUriException("appEphemeralPubKey is not a valid compressed secp256k1 point")
        }

        return DashKeyRequest(appEphemeralPubKey, contractId, label, network)
    }

    /** True if [uri] uses the `dash-key:` scheme. */
    fun isKeyUri(uri: String): Boolean = uri.startsWith(KEY_SCHEME)

    // ── internals ────────────────────────────────────────────────────────────────────

    /**
     * Validates the shared URI envelope: scheme (no `//`), presence of a query, required `n`/`v`
     * params, `v == 1`, and a known network. Returns the Base58 body and the decoded network.
     */
    private fun parseEnvelope(uri: String, scheme: String): Pair<String, DashConnectNetwork> {
        if (!uri.startsWith(scheme)) {
            throw DashConnectUriException("expected scheme $scheme")
        }
        val afterScheme = uri.substring(scheme.length)
        if (afterScheme.startsWith("//")) {
            throw DashConnectUriException("scheme must not be followed by //")
        }
        val queryIndex = afterScheme.indexOf('?')
        if (queryIndex < 0) {
            throw DashConnectUriException("missing query component")
        }
        val body = afterScheme.substring(0, queryIndex)
        if (body.isEmpty()) {
            throw DashConnectUriException("missing payload")
        }
        val query = afterScheme.substring(queryIndex + 1)
        val params = parseQuery(query)

        val version = params["v"] ?: throw DashConnectUriException("missing v param")
        if (version.toIntOrNull() != VERSION) {
            throw DashConnectUriException("unsupported version: $version")
        }
        val networkCode = params["n"] ?: throw DashConnectUriException("missing n param")
        val network = DashConnectNetwork.fromCode(networkCode)
            ?: throw DashConnectUriException("unknown network: $networkCode")

        return body to network
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            pair.substring(0, eq) to pair.substring(eq + 1)
        }.toMap()
    }

    private fun decodeBase58(body: String): ByteArray = try {
        Base58.decode(body) // plain Base58, no checksum
    } catch (ex: Exception) {
        throw DashConnectUriException("invalid Base58 payload", ex)
    }
}
