/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.payments.parsers

import de.schildbach.wallet.Constants
import org.bitcoinj.core.Address
import org.bitcoinj.core.Base58

object AddressParser {
    private val PATTERN_BITCOIN_ADDRESS = Regex("[${Base58.ALPHABET.joinToString(separator = "")}]{20,40}")

    fun matches(inputText: String): Boolean {
        return PATTERN_BITCOIN_ADDRESS.matches(inputText)
    }

    fun findAll(inputText: String): List<IntRange> {
        val matches = PATTERN_BITCOIN_ADDRESS.findAll(inputText)
        val validRanges = mutableListOf<IntRange>()

        for (match in matches) {
            val addressCandidate = match.value

            try {
                Address.fromString(Constants.NETWORK_PARAMETERS, addressCandidate)
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
