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
 * Minimal, dashj-free network descriptor replacing `org.bitcoinj.core.NetworkParameters` in the
 * `common` module's APIs. Header values, ids and URI schemes are exactly those of the dashj
 * network parameter classes (dashj 22.0.3), so address validation and script/address round-trips
 * behave identically.
 */
class AddressNetwork(
    /** Network id string, exactly as `NetworkParameters.getId()` returns it. */
    val id: String,
    /** Payment URI scheme, exactly as `NetworkParameters.getUriScheme()` returns it. */
    val uriScheme: String,
    /** First byte of a base58check-encoded P2PKH address. */
    val addressHeader: Int,
    /** First byte of a base58check-encoded P2SH address. */
    val p2shHeader: Int,
    /** Human-readable part of bech32 segwit addresses, or null when the network has none. */
    val segwitHrp: String? = null,
    /** Largest representable monetary amount, in smallest units (`NetworkParameters.getMaxMoney()`). */
    val maxMoney: Long = MAX_MONEY_DUFFS,
    /** BIP70 network id, exactly as `NetworkParameters.getPaymentProtocolId()` returns it. */
    val paymentProtocolId: String = PAYMENT_PROTOCOL_ID_MAINNET,
    /**
     * First byte of a base58check-encoded WIF private key, exactly as
     * `NetworkParameters.getDumpedPrivateKeyHeader()` returns it (dashj 22.0.3:
     * `MainNetParams` = 204/0xcc, `TestNet3Params`/`DevNetParams` = 239/0xef, Bitcoin = 128/0x80).
     */
    val dumpedPrivateKeyHeader: Int = 128
) {
    companion object {
        /** `Coin.COIN.multiply(22_000_000)` — dashj's `NetworkParameters.MAX_MONEY`. */
        const val MAX_MONEY_DUFFS = 22_000_000L * 100_000_000L

        const val ID_MAINNET = "org.darkcoin.production"
        const val ID_TESTNET = "org.darkcoin.test"
        const val ID_DEVNET = "org.dash.dev"

        const val DASH_SCHEME = "dash"
        const val BITCOIN_SCHEME = "bitcoin"

        const val PAYMENT_PROTOCOL_ID_MAINNET = "main"
        const val PAYMENT_PROTOCOL_ID_TESTNET = "test"
        const val PAYMENT_PROTOCOL_ID_DEVNET = "dev"

        @JvmField
        val DASH_MAINNET = AddressNetwork(ID_MAINNET, DASH_SCHEME, 76, 16, dumpedPrivateKeyHeader = 204)

        @JvmField
        val DASH_TESTNET = AddressNetwork(
            ID_TESTNET, DASH_SCHEME, 140, 19,
            paymentProtocolId = PAYMENT_PROTOCOL_ID_TESTNET,
            dumpedPrivateKeyHeader = 239
        )

        /** Devnets share testnet's address space. */
        @JvmField
        val DASH_DEVNET = AddressNetwork(
            ID_DEVNET, DASH_SCHEME, 140, 19,
            paymentProtocolId = PAYMENT_PROTOCOL_ID_DEVNET,
            dumpedPrivateKeyHeader = 239
        )

        @JvmField
        val BITCOIN_MAINNET = AddressNetwork(ID_MAINNET_BITCOIN, BITCOIN_SCHEME, 0, 5, "bc", dumpedPrivateKeyHeader = 128)

        /** Mirrors `NetworkParameters.fromPmtProtocolID` for the networks the app supports; null when unknown. */
        @JvmStatic
        fun fromPaymentProtocolId(pmtProtocolId: String): AddressNetwork? = when (pmtProtocolId) {
            PAYMENT_PROTOCOL_ID_MAINNET -> DASH_MAINNET
            PAYMENT_PROTOCOL_ID_TESTNET -> DASH_TESTNET
            PAYMENT_PROTOCOL_ID_DEVNET -> DASH_DEVNET
            else -> null
        }

        /**
         * Resolves a network descriptor from a dashj network id
         * (`WalletDataProvider.networkId` / `NetworkParameters.getId()`).
         */
        @JvmStatic
        fun fromId(id: String): AddressNetwork = when {
            id == ID_MAINNET -> DASH_MAINNET
            id == ID_TESTNET -> DASH_TESTNET
            id.startsWith(ID_DEVNET) -> DASH_DEVNET
            else -> throw IllegalArgumentException("Unknown network id: $id")
        }

        /**
         * The Dash network whose address space contains the given base58 address, mirroring
         * `Address.getParametersFromAddress` over dashj's default network set (testnet is
         * matched before mainnet; devnets share testnet's version bytes and thus resolve to
         * [DASH_TESTNET], exactly like the dashj original).
         *
         * @throws AddressFormatException if the string is not a valid base58check address on any Dash network.
         */
        @JvmStatic
        fun fromDashAddress(address: String): AddressNetwork {
            val version = AddressUtils.versionOf(address)
            for (network in listOf(DASH_TESTNET, DASH_MAINNET)) {
                if (version == network.addressHeader || version == network.p2shHeader) {
                    return network
                }
            }
            throw AddressFormatException.InvalidPrefix("No network found for $address")
        }
    }

    /** True if [version] is this network's P2PKH or P2SH address version byte. */
    fun acceptsVersion(version: Int): Boolean = version == addressHeader || version == p2shHeader

    override fun equals(other: Any?): Boolean = other is AddressNetwork && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id
}

private const val ID_MAINNET_BITCOIN = "org.bitcoin.production"

/**
 * Dashj-free base58 address helpers mirroring the behavior of dashj's `Address`/`LegacyAddress`.
 */
object AddressUtils {

    /** Decoded (version, hash160) of a base58check address. */
    class DecodedAddress(val version: Int, val hash160: ByteArray)

    /**
     * Decodes and checksum-validates a base58 address without any network check
     * (mirrors `LegacyAddress` decoding: 20-byte payload required).
     */
    @JvmStatic
    @Throws(AddressFormatException::class)
    fun decode(address: String): DecodedAddress {
        val versionAndDataBytes = Base58.decodeChecked(address)
        val version = versionAndDataBytes[0].toInt() and 0xFF
        val payload = versionAndDataBytes.copyOfRange(1, versionAndDataBytes.size)
        if (payload.size != 20) {
            throw AddressFormatException.InvalidDataLength("Legacy addresses are 20 byte (160 bit) hashes, but got: " + payload.size)
        }
        return DecodedAddress(version, payload)
    }

    /** The version byte of a base58check address (checksum-validated). */
    @JvmStatic
    @Throws(AddressFormatException::class)
    fun versionOf(address: String): Int = decode(address).version

    /**
     * Validates [address] against [network], mirroring `Address.fromString(params, address)`:
     * base58check first and, when the network has a segwit HRP, bech32 as a fallback.
     *
     * @throws AddressFormatException on invalid input or wrong network.
     */
    @JvmStatic
    @Throws(AddressFormatException::class)
    fun verify(network: AddressNetwork, address: String) {
        try {
            val decoded = decode(address)
            if (!network.acceptsVersion(decoded.version)) {
                throw AddressFormatException.WrongNetwork(decoded.version)
            }
        } catch (e: AddressFormatException.WrongNetwork) {
            throw e
        } catch (e: AddressFormatException) {
            if (network.segwitHrp != null) {
                SegwitAddress.fromBech32(network, address)
            } else {
                throw e
            }
        }
    }

    /** True when [address] passes [verify] for [network]. */
    @JvmStatic
    fun isValid(network: AddressNetwork, address: String): Boolean {
        return try {
            verify(network, address)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Encodes a (version, hash160) pair back to base58check. */
    @JvmStatic
    fun encode(version: Int, hash160: ByteArray): String = Base58.encodeChecked(version, hash160)
}
