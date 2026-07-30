/*
 * Copyright 2022 Dash Core Group.
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

open class AddressParser(pattern: String, val params: AddressNetwork?, private val ignoreCase: Boolean = false) {
    /** Pattern-only constructor for parsers that skip network validation. */
    constructor(pattern: String) : this(pattern, null)

    companion object {
        val PATTERN_BITCOIN_ADDRESS = "[${Base58.ALPHABET.joinToString(separator = "")}]{20,40}"
        private const val PATTERN_ETHEREUM_ADDRESS = "0x[a-fA-F0-9]{40}"
        const val PATTERN_BECH32_ADDRESS = "1[a-z0-9]{39,59}" // taproot goes to 59
        fun getDashAddressParser(params: AddressNetwork): AddressParser {
            return AddressParser(PATTERN_BITCOIN_ADDRESS, params)
        }

        fun getBase58AddressParser(params: AddressNetwork? = null): AddressParser {
            return AddressParser(PATTERN_BITCOIN_ADDRESS, params)
        }

        fun getEthereumAddressParser(): AddressParser {
            return AddressParser(PATTERN_ETHEREUM_ADDRESS, null)
        }
    }

    private val addressPattern = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

    open fun exactMatch(inputText: String): Boolean {
        return addressPattern.matches(inputText) && isAddressValid(inputText)
    }

    open fun findAll(inputText: String): List<IntRange> {
        val matches = addressPattern.findAll(inputText)
        val validRanges = mutableListOf<IntRange>()

        for (match in matches) {
            val addressCandidate = match.value
            println("candidate ${match.value}")

            try {
                verifyAddress(addressCandidate)
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                validRanges.add(startIndex..endIndex)
            } catch (e: Exception) {
                // Invalid address, skipping
                println(e)
            }
        }

        return validRanges
    }

    /**
     * Canonicalizes the case of a scanned or pasted address: bech32 QR codes commonly carry
     * the all-uppercase form (alphanumeric mode), which is lowercased when the lowercase form
     * is a valid address. Only inputs reported by [isCaseInsensitiveFormat] are ever rewritten:
     * Base58 and EIP-55 are case-significant, and where they are validated by pattern only
     * (no [params], so no checksum) an all-caps corrupt address could otherwise be "repaired"
     * into a different, plausible-looking one instead of being rejected.
     */
    fun normalizeCase(input: String): String {
        return if (isCaseInsensitiveFormat(input) &&
            input.any { it.isUpperCase() } &&
            input.none { it.isLowerCase() } &&
            exactMatch(input.lowercase())
        ) {
            input.lowercase()
        } else {
            input
        }
    }

    /**
     * Whether [input] is a candidate for a case-insensitive address format. Parsers that mix
     * case-insensitive bech32 with case-sensitive alternatives override this per input.
     */
    protected open fun isCaseInsensitiveFormat(input: String): Boolean = ignoreCase

    protected open fun verifyAddress(addressCandidate: String) {
        params?.let { AddressUtils.verify(it, addressCandidate) }
    }

    /**
     * True if [addressCandidate] passes [verifyAddress] without throwing.
     */
    fun isValidAddress(addressCandidate: String): Boolean {
        return try {
            verifyAddress(addressCandidate)
            true
        } catch (e: Exception) {
            false
        }
    }

    protected fun isAddressValid(addressCandidate: String) = try {
        verifyAddress(addressCandidate)
        true
    } catch (_: Exception) {
        false
    }
}
