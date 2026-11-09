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

package de.schildbach.wallet.service.platform.sdk

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressFormatException
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.AddressUtils
import org.dash.wallet.common.payments.parsers.Base58
import org.dash.wallet.common.payments.parsers.PaymentURI

// Pure, host-testable model of the Advanced-mode "External Send" (MO-1121):
// which balance pays, what the typed destination is, and which pairs the
// wallet supports. No native, no Android, no I/O.

/** The four balances an Advanced-mode send can be paid from. */
enum class SendBalance { CORE, SHIELDED, PLATFORM, IDENTITY }

/**
 * A fully validated external destination. [display] is the canonical form to show
 * the user; [toString] is redacted (addresses and identity ids are never logged).
 */
sealed class SendDestination {
    abstract val display: String

    /** A Core L1 base58 address of the wallet's network (P2PKH or P2SH). */
    data class Core(val address: String) : SendDestination() {
        override val display: String get() = address
        override fun toString(): String = "Core(<redacted>)"
    }

    /** A bech32m Orchard address (`dash1z…` / `tdash1z…`), lowercase. */
    data class Shielded(val address: String) : SendDestination() {
        override val display: String get() = address
        override fun toString(): String = "Shielded(<redacted>)"
    }

    /** A bech32m DIP-18 Platform address (`dash1k…` P2PKH / `dash1s…` P2SH, `tdash1…` off mainnet), lowercase. */
    data class Platform(val address: String) : SendDestination() {
        override val display: String get() = address
        override fun toString(): String = "Platform(<redacted>)"
    }

    /** An identity id: base58 of exactly 32 bytes. */
    data class Identity(val identityIdBase58: String) : SendDestination() {
        override val display: String get() = identityIdBase58
        override fun toString(): String = "Identity(<redacted>)"
    }
}

/** The result of [detectSendDestination]. */
sealed class DestinationParse {
    /**
     * A destination of the wallet's network. [amount] is set only for a `dash:` payment
     * URI that carried a positive one (any destination type, see [detectSendDestination]).
     */
    data class Valid(val destination: SendDestination, val amount: Dash? = null) : DestinationParse() {
        override fun toString(): String = "Valid($destination, amount=${if (amount == null) "none" else "<redacted>"})"
    }

    /** Blank input. */
    object Empty : DestinationParse()

    /** A well-formed address of another Dash network (mainnet vs testnet/devnet). */
    object WrongNetwork : DestinationParse()

    /** Anything else: bad checksum, unknown type, wrong length, a BIP70 `r=` link, … */
    object Invalid : DestinationParse()
}

/** DIP-18 bech32m type byte of a P2PKH Platform address (rs-dpp `PlatformAddress::P2PKH_TYPE`). */
internal const val PLATFORM_P2PKH_TYPE_BYTE = 0xb0

/** DIP-18 bech32m type byte of a P2SH Platform address (rs-dpp `PlatformAddress::P2SH_TYPE`). */
internal const val PLATFORM_P2SH_TYPE_BYTE = 0x80

/** DIP-18 bech32m type byte of an Orchard address. */
private const val ORCHARD_ADDRESS_TYPE_BYTE = 0x10

/** Orchard raw address length: 11-byte diversifier + 32-byte pk_d. */
private const val ORCHARD_RAW_LENGTH = 43

/** Platform address hash length (hash160). */
private const val PLATFORM_HASH_LENGTH = 20

/** Identity ids are 32 bytes. */
internal const val IDENTITY_ID_LENGTH = 32

private const val MAINNET_HRP = "dash"
private const val TESTNET_HRP = "tdash"

/** DIP-18 HRP of [network]: `dash` on mainnet, `tdash` everywhere else. */
internal fun platformHrp(network: AddressNetwork): String =
    if (network.id == AddressNetwork.ID_MAINNET) MAINNET_HRP else TESTNET_HRP

/**
 * A Platform address decoded to the SDK's storage shape: [addressType] 0 = P2PKH,
 * 1 = P2SH (the `FundingInput` / `CreditOutput` convention), [hash] its 20-byte hash.
 */
class PlatformAddressBytes(val addressType: Int, val hash: ByteArray) {
    override fun toString(): String = "PlatformAddressBytes(type=$addressType, <redacted>)"
}

/**
 * Decode a bech32m Platform address of [network], or null when it is not one (bad
 * checksum, other HRP, wrong length, unknown type byte).
 */
internal fun decodePlatformAddress(address: String, network: AddressNetwork): PlatformAddressBytes? {
    val decoded = Bech32m.decode(address.trim()) ?: return null
    if (decoded.hrp != platformHrp(network)) return null
    val data = decoded.data
    if (data.size != 1 + PLATFORM_HASH_LENGTH) return null
    val type = when (data[0].toInt() and 0xff) {
        PLATFORM_P2PKH_TYPE_BYTE -> 0
        PLATFORM_P2SH_TYPE_BYTE -> 1
        else -> return null
    }
    return PlatformAddressBytes(type, data.copyOfRange(1, data.size))
}

/**
 * Whether [address] is a well-formed P2PKH Platform address (type byte
 * [PLATFORM_P2PKH_TYPE_BYTE], 20-byte hash) of either Dash HRP. False for P2SH and for
 * anything that is not a Platform address. The network is checked separately.
 */
internal fun isP2pkhPlatformAddress(address: String): Boolean {
    val decoded = Bech32m.decode(address.trim()) ?: return false
    if (decoded.hrp != MAINNET_HRP && decoded.hrp != TESTNET_HRP) return false
    val data = decoded.data
    return data.size == 1 + PLATFORM_HASH_LENGTH && (data[0].toInt() and 0xff) == PLATFORM_P2PKH_TYPE_BYTE
}

/** The 32 bytes of a base58 identity id, or null when [identityIdBase58] is not one. */
internal fun decodeIdentityId(identityIdBase58: String): ByteArray? {
    val trimmed = identityIdBase58.trim()
    if (trimmed.isEmpty()) return null
    val bytes = try {
        Base58.decode(trimmed)
    } catch (e: AddressFormatException) {
        return null
    }
    return bytes.takeIf { it.size == IDENTITY_ID_LENGTH }
}

private val DASH_NETWORKS = listOf(AddressNetwork.DASH_MAINNET, AddressNetwork.DASH_TESTNET)

/**
 * Classify what the user typed or scanned as an external send destination of
 * [network]. The WHOLE input is checked: the Core base58check checksum and version
 * byte; the bech32m checksum, HRP (= network), payload length and type byte for
 * Platform/Shielded; the decoded length (32) for an identity id. Leading and trailing
 * whitespace is ignored; bech32m must be all-lowercase or all-uppercase (BIP-350) and
 * is returned lowercase.
 *
 * A `dash:` payment URI resolves to its destination plus its optional amount: a Core
 * address through the shared [PaymentURI] parser; a Shielded, Platform or identity path
 * (what Advanced Receive's amount requests produce,
 * `ReceiveViewModel.amountRequestPayload`) through [detectNonCoreDashUri]. A URI that
 * carries a BIP70 payment request (`r=`) is [DestinationParse.Invalid] here: the
 * merchant's invoice flow owns it, and paying the fallback address would bypass the
 * invoice.
 *
 * Pure and total: it never throws, whatever the input (anything unexpected is
 * [DestinationParse.Invalid]).
 */
fun detectSendDestination(input: String, network: AddressNetwork): DestinationParse = try {
    detectSendDestinationOrThrow(input, network)
} catch (e: Exception) {
    // A decoder bug on hostile input (scanned, typed or on the clipboard) must never
    // crash the screen; the input is simply not a destination.
    DestinationParse.Invalid
}

private fun detectSendDestinationOrThrow(input: String, network: AddressNetwork): DestinationParse {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return DestinationParse.Empty

    if (trimmed.startsWith("${AddressNetwork.DASH_SCHEME}:", ignoreCase = true)) {
        return detectDashUri(trimmed, network)
    }
    return detectPlainDestination(trimmed, network)
}

/** A bare (non-URI) destination: Core, then bech32m Platform/Orchard, then an identity id. */
private fun detectPlainDestination(input: String, network: AddressNetwork): DestinationParse {
    detectCoreAddress(input, network)?.let { return it }
    detectBech32mAddress(input, network)?.let { return it }
    decodeIdentityId(input)?.let {
        return DestinationParse.Valid(SendDestination.Identity(input))
    }
    return DestinationParse.Invalid
}

/** A base58check Core address: Valid / WrongNetwork, or null when the input is not one at all. */
private fun detectCoreAddress(input: String, network: AddressNetwork): DestinationParse? {
    val decoded = try {
        AddressUtils.decode(input)
    } catch (e: AddressFormatException) {
        return null
    }
    if (network.acceptsVersion(decoded.version)) {
        return DestinationParse.Valid(SendDestination.Core(input))
    }
    // A valid base58check hash160 of ANOTHER Dash network is a wrong-network address;
    // any other version byte (a Bitcoin address, say) is simply invalid.
    return if (DASH_NETWORKS.any { it.acceptsVersion(decoded.version) }) {
        DestinationParse.WrongNetwork
    } else {
        DestinationParse.Invalid
    }
}

/** A bech32m Platform or Orchard address: Valid / WrongNetwork / Invalid, or null when not bech32m. */
private fun detectBech32mAddress(input: String, network: AddressNetwork): DestinationParse? {
    val decoded = Bech32m.decode(input) ?: return null
    if (decoded.hrp != MAINNET_HRP && decoded.hrp != TESTNET_HRP) return DestinationParse.Invalid
    val data = decoded.data
    if (data.isEmpty()) return DestinationParse.Invalid
    val type = data[0].toInt() and 0xff
    val kindIsShielded = when {
        type == ORCHARD_ADDRESS_TYPE_BYTE && data.size == 1 + ORCHARD_RAW_LENGTH -> true
        (type == PLATFORM_P2PKH_TYPE_BYTE || type == PLATFORM_P2SH_TYPE_BYTE) &&
            data.size == 1 + PLATFORM_HASH_LENGTH -> false
        else -> return DestinationParse.Invalid
    }
    if (decoded.hrp != platformHrp(network)) return DestinationParse.WrongNetwork
    val canonical = input.lowercase()
    return DestinationParse.Valid(
        if (kindIsShielded) SendDestination.Shielded(canonical) else SendDestination.Platform(canonical)
    )
}

private fun detectDashUri(input: String, network: AddressNetwork): DestinationParse {
    val body = input.substring(AddressNetwork.DASH_SCHEME.length + 1)
    val path = body.substringBefore('?')
    // A Core address (or no address at all) keeps the shared PaymentURI parser, unchanged.
    if (path.isEmpty() || detectCoreAddress(path, network) != null) {
        return detectCoreDashUri(input, network)
    }
    val query = if ('?' in body) body.substringAfter('?') else ""
    return detectNonCoreDashUri(path, query, network)
}

/**
 * `dash:<shielded|platform|identity>[?amount=<DASH>]`. The query is read strictly (BIP21
 * rules): a BIP70 `r` or any unknown required (`req-…`) parameter is
 * [DestinationParse.Invalid], as is a repeated or malformed `amount`; other optional
 * parameters (label, message, …) are ignored.
 */
private fun detectNonCoreDashUri(path: String, query: String, network: AddressNetwork): DestinationParse {
    var amount: Dash? = null
    var amountSeen = false
    for (param in query.split('&')) {
        if (param.isEmpty()) continue
        val key = param.substringBefore('=')
        val value = param.substringAfter('=', missingDelimiterValue = "")
        when {
            key == "amount" -> {
                if (amountSeen) return DestinationParse.Invalid
                amountSeen = true
                val duffs = parseUriAmountDuffs(value, network) ?: return DestinationParse.Invalid
                amount = Dash(duffs).takeIf { it.isPositive }
            }
            key == "r" || key.startsWith("req-") -> return DestinationParse.Invalid
            else -> Unit
        }
    }
    return when (val destination = detectPlainDestination(path, network)) {
        is DestinationParse.Valid -> DestinationParse.Valid(destination.destination, amount)
        DestinationParse.WrongNetwork -> DestinationParse.WrongNetwork
        DestinationParse.Empty, DestinationParse.Invalid -> DestinationParse.Invalid
    }
}

private val URI_AMOUNT = Regex("^[0-9]{1,8}(\\.[0-9]{1,8})?$")

/** A BIP21 `amount` (decimal DASH, at most 8 places, at most [AddressNetwork.maxMoney]) in duffs, or null. */
private fun parseUriAmountDuffs(value: String, network: AddressNetwork): Long? {
    if (!URI_AMOUNT.matches(value)) return null
    val duffs = java.math.BigDecimal(value).movePointRight(8).longValueExact()
    return duffs.takeIf { it <= network.maxMoney }
}

private fun detectCoreDashUri(input: String, network: AddressNetwork): DestinationParse {
    // Parsed without a network expectation, so a wrong-network address is reported
    // as such instead of as a generic parse failure.
    val uri = try {
        PaymentURI(null, input)
    } catch (e: PaymentURI.ParseException) {
        return DestinationParse.Invalid
    }
    if (uri.paymentRequestUrl != null) return DestinationParse.Invalid
    val address = uri.address ?: return DestinationParse.Invalid
    return when (val core = detectCoreAddress(address, network)) {
        is DestinationParse.Valid -> {
            val amount = uri.amount?.let { Dash(it.value) }?.takeIf { it.isPositive }
            DestinationParse.Valid(core.destination, amount)
        }
        null -> DestinationParse.Invalid
        else -> core
    }
}

/** Whether a pair can be sent, and if not, why (the UI maps each to its own copy). */
enum class SendSupport {
    SUPPORTED,

    /** The SDK (or the wallet) cannot do this pair yet. */
    NOT_SUPPORTED_YET,

    /** A Core-funded identity top-up is only possible for an identity this wallet manages. */
    NEEDS_OWN_IDENTITY,

    /** The source is the identity balance and the user has no identity. */
    NO_IDENTITY
}

/**
 * The one support matrix of the external send, enforced by the UI AND by
 * [ExternalSendService.send]:
 *
 * | From \ To | Core | Shielded | Platform | Identity |
 * |---|---|---|---|---|
 * | Core | yes | yes | yes, P2PKH only (1) | own identity only (2) |
 * | Shielded | yes | yes | yes | yes |
 * | Platform | yes (3) | yes (4) | yes | yes |
 * | Identity | yes | yes (5) | yes | yes, not to itself |
 *
 * 1. `ManagedPlatformWallet.fundFromAssetLockExternal`: the payee gets the explicit
 *    amount, the wallet's own Platform address the remainder (and pays the fee). The SDK
 *    takes P2PKH recipients only, so a P2SH (`dash1s…`) destination is
 *    [SendSupport.NOT_SUPPORTED_YET] here and refused by [ExternalSendService.send]
 *    before anything is built. A lock interrupted after it was built is resumed with its
 *    persisted recipients ([PendingPlatformFunding]).
 * 2. The Core top-up's resume gate keys on the managed identity's registration index;
 *    for someone else's identity a killed attempt could not be resumed (double-pay
 *    risk). [destinationIsOwnIdentity] null (could not tell) fails closed.
 * 3. The partial-amount `ManagedPlatformWallet.withdrawCredits(coreAddress, inputs, …)`
 *    with inputs the wallet selects ([selectPlatformWithdrawalInputs]); amounts below the
 *    withdrawal minimum ([PLATFORM_MIN_WITHDRAWAL_CREDITS]) are refused before signing.
 * 4. `shieldedShieldToRecipient` (Type 15 to a third party's Orchard address; the SDK
 *    selects the Platform-address inputs).
 * 5. `shieldedShieldFromIdentityToRecipient` (Type 21 to a third party's Orchard address).
 *
 * In 4 and 5 the SDK refuses an Orchard address the wallet's own shielded account
 * recognizes (a self-shield is not an external send) before building anything.
 */
fun externalSendSupport(
    source: SendBalance,
    destination: SendDestination,
    hasIdentity: Boolean,
    destinationIsOwnIdentity: Boolean?
): SendSupport {
    if (source == SendBalance.IDENTITY && !hasIdentity) return SendSupport.NO_IDENTITY
    return when (source) {
        SendBalance.CORE -> when (destination) {
            is SendDestination.Core, is SendDestination.Shielded -> SendSupport.SUPPORTED
            is SendDestination.Platform ->
                if (isP2pkhPlatformAddress(destination.address)) SendSupport.SUPPORTED else SendSupport.NOT_SUPPORTED_YET
            is SendDestination.Identity ->
                if (destinationIsOwnIdentity == true) SendSupport.SUPPORTED else SendSupport.NEEDS_OWN_IDENTITY
        }
        SendBalance.SHIELDED -> SendSupport.SUPPORTED
        SendBalance.PLATFORM -> SendSupport.SUPPORTED
        SendBalance.IDENTITY -> when (destination) {
            is SendDestination.Core, is SendDestination.Shielded, is SendDestination.Platform -> SendSupport.SUPPORTED
            // A transfer from the user's identity to itself moves nothing and pays a fee.
            is SendDestination.Identity ->
                if (destinationIsOwnIdentity == true) SendSupport.NOT_SUPPORTED_YET else SendSupport.SUPPORTED
        }
    }
}
