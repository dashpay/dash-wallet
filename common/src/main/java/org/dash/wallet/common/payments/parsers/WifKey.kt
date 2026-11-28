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

/**
 * WIF ("dumped") private key encode/decode on top of the Step A [Base58] port — a dashj-free
 * mirror of `org.bitcoinj.core.DumpedPrivateKey` (dashj 22.0.3):
 *
 * - base58check payload = 1 version byte ([AddressNetwork.dumpedPrivateKeyHeader]: Dash mainnet
 *   204/0xcc, testnet+devnets 239/0xef) + 32 key bytes + optional compressed-pubkey flag byte 0x01;
 * - decoding accepts 32- or 33-byte key payloads, anything else is
 *   [AddressFormatException.InvalidDataLength] — same rule as the `DumpedPrivateKey` constructor;
 * - [compressed] is true only for a 33-byte payload whose last byte is exactly 1, matching
 *   `DumpedPrivateKey.isPubKeyCompressed()`;
 * - network-checked decode throws [AddressFormatException.WrongNetwork] like
 *   `DumpedPrivateKey.fromBase58(params, base58)`.
 */
class WifKey private constructor(
    /** The WIF version byte that was encoded (e.g. 204 for Dash mainnet). */
    val version: Int,
    private val bytes: ByteArray
) {
    init {
        if (bytes.size != 32 && bytes.size != 33) {
            throw AddressFormatException.InvalidDataLength(
                "Wrong number of bytes for a private key (32 or 33): " + bytes.size
            )
        }
    }

    /** The raw 32-byte private key. */
    val keyBytes: ByteArray
        get() = bytes.copyOf(32)

    /** Mirrors `DumpedPrivateKey.isPubKeyCompressed()`. */
    val compressed: Boolean
        get() = bytes.size == 33 && bytes[32].toInt() == 1

    /** Re-encodes with the version byte this key was decoded/created with. */
    fun toBase58(): String = Base58.encodeChecked(version, bytes)

    override fun equals(other: Any?): Boolean =
        other is WifKey && other.version == version && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * version + bytes.contentHashCode()

    override fun toString(): String = toBase58()

    companion object {
        /**
         * Decodes a WIF string without any network check (checksum still enforced).
         *
         * @throws AddressFormatException on bad base58, bad checksum or bad payload length
         */
        @JvmStatic
        @Throws(AddressFormatException::class)
        fun decode(wif: String): WifKey {
            val versionAndDataBytes = Base58.decodeChecked(wif)
            val version = versionAndDataBytes[0].toInt() and 0xFF
            val bytes = versionAndDataBytes.copyOfRange(1, versionAndDataBytes.size)
            return WifKey(version, bytes)
        }

        /**
         * Decodes a WIF string, requiring [network]'s private key version byte — the equivalent
         * of `DumpedPrivateKey.fromBase58(params, wif)`.
         *
         * Like dashj, the version byte is checked before the payload length, so a wrong-network
         * string with a malformed payload throws [AddressFormatException.WrongNetwork], not
         * [AddressFormatException.InvalidDataLength].
         *
         * @throws AddressFormatException.WrongNetwork when the version byte belongs to another network
         */
        @JvmStatic
        @Throws(AddressFormatException::class)
        fun decode(wif: String, network: AddressNetwork): WifKey {
            val versionAndDataBytes = Base58.decodeChecked(wif)
            val version = versionAndDataBytes[0].toInt() and 0xFF
            if (version != network.dumpedPrivateKeyHeader) {
                throw AddressFormatException.WrongNetwork(version)
            }
            return WifKey(version, versionAndDataBytes.copyOfRange(1, versionAndDataBytes.size))
        }

        /**
         * Decodes a WIF string on whichever Dash network matches its version byte, mirroring
         * `DumpedPrivateKey.fromBase58(null, wif)` over dashj's default network set
         * (testnet is tried before mainnet; devnets share testnet's version byte).
         *
         * Like dashj, the version byte is inspected before the payload length, so an unknown
         * version byte with a malformed payload throws [AddressFormatException.InvalidPrefix],
         * not [AddressFormatException.InvalidDataLength].
         *
         * @return the key and the matching network
         * @throws AddressFormatException.InvalidPrefix when no Dash network matches
         */
        @JvmStatic
        @Throws(AddressFormatException::class)
        fun decodeDash(wif: String): Pair<WifKey, AddressNetwork> {
            val versionAndDataBytes = Base58.decodeChecked(wif)
            val version = versionAndDataBytes[0].toInt() and 0xFF
            for (network in listOf(AddressNetwork.DASH_TESTNET, AddressNetwork.DASH_MAINNET)) {
                if (version == network.dumpedPrivateKeyHeader) {
                    return Pair(WifKey(version, versionAndDataBytes.copyOfRange(1, versionAndDataBytes.size)), network)
                }
            }
            throw AddressFormatException.InvalidPrefix("No network found for version " + version)
        }

        /**
         * Encodes a raw 32-byte private key as WIF for [network], appending the 0x01
         * compressed-pubkey flag when [compressed] — the equivalent of
         * `new DumpedPrivateKey(params, keyBytes, compressed).toBase58()` for ECDSA keys.
         */
        @JvmStatic
        fun encode(network: AddressNetwork, keyBytes: ByteArray, compressed: Boolean): String {
            require(keyBytes.size == 32) { "Private keys must be 32 bytes" }
            val payload = if (compressed) keyBytes.copyOf(33).also { it[32] = 1 } else keyBytes
            return Base58.encodeChecked(network.dumpedPrivateKeyHeader, payload)
        }
    }
}
