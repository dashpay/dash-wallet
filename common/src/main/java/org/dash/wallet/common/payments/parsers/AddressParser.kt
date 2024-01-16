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

import org.bitcoinj.core.Address
import org.bitcoinj.core.Base58
import org.bitcoinj.core.NetworkParameters

class AddressParser(pattern: String, val params: NetworkParameters?) {
    companion object {
        private val PATTERN_BITCOIN_ADDRESS = "[${Base58.ALPHABET.joinToString(separator = "")}]{20,40}"
        private val PATTERN_ETHEREUM_ADDRESS = "0x[a-fA-F0-9]{40}"
        fun getDashAddressParser(params: NetworkParameters): AddressParser {
            return AddressParser(PATTERN_BITCOIN_ADDRESS, params)
        }

        fun getBitcoinAddressParser(): AddressParser  {
            return AddressParser(PATTERN_BITCOIN_ADDRESS, BitcoinMainNetParams())
        }

        fun getEthereumAddressParser(): AddressParser {
            return AddressParser(PATTERN_ETHEREUM_ADDRESS, null)
        }

        fun get(currency: String, params: NetworkParameters? = null): AddressParser {
            return when (currency) {
                "bitcoin" -> getBitcoinAddressParser()
                "dash" -> getDashAddressParser(params!!)
                else -> getEthereumAddressParser()
            }
        }
    }

    private val addressPattern = Regex(pattern)

    fun exactMatch(inputText: String): Boolean {
        return addressPattern.matches(inputText)
    }

    fun findAll(inputText: String): List<IntRange> {
        val matches = addressPattern.findAll(inputText)
        val validRanges = mutableListOf<IntRange>()

        for (match in matches) {
            val addressCandidate = match.value

            try {
                params?.let { Address.fromString(params, addressCandidate) }
                val startIndex = match.range.first
                val endIndex = match.range.last + 1
                validRanges.add(startIndex..endIndex)
            } catch (e: Exception) {
                // Invalid address, skipping
            }
        }

        return validRanges
    }
}
