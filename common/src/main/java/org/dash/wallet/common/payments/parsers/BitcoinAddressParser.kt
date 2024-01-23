/*
 * Copyright 2024 Dash Core Group.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.payments.parsers

import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.NetworkParameters

class BitcoinAddressParser(params: NetworkParameters) : AddressParser(PATTERN_BITCOIN_ADDRESS, params) {
    private val bech32Parser = Bech32AddressParser(39, 59, params)

    override fun exactMatch(inputText: String): Boolean {
        return super.exactMatch(inputText) || bech32Parser.exactMatch(inputText)
    }

    override fun findAll(inputText: String): List<IntRange> {
        val result = arrayListOf<IntRange>()
        result.addAll(super.findAll(inputText))
        result.addAll(bech32Parser.findAll(inputText))
        return result
    }

    override fun verifyAddress(addressCandidate: String) {
        params?.let {
            try {
                Address.fromString(params, addressCandidate)
            } catch (e: AddressFormatException) {
                SegwitAddress.fromBech32(params, addressCandidate)
            }
        }
    }
}
