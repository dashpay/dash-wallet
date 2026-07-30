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

class BitcoinAddressParser(params: AddressNetwork) : AddressParser(PATTERN_BITCOIN_ADDRESS, params) {
    /** Mainnet constructor. */
    constructor() : this(AddressNetwork.BITCOIN_MAINNET)

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

    // Only the segwit bech32 alternative is case-insensitive; legacy Base58 keeps its case.
    override fun isCaseInsensitiveFormat(input: String): Boolean {
        val hrp = params?.segwitHrp ?: return false
        return input.startsWith("${hrp}1", ignoreCase = true)
    }

    override fun verifyAddress(addressCandidate: String) {
        params?.let {
            try {
                val decoded = AddressUtils.decode(addressCandidate)
                if (!it.acceptsVersion(decoded.version)) {
                    throw AddressFormatException.WrongNetwork(decoded.version)
                }
            } catch (e: AddressFormatException.WrongNetwork) {
                throw e
            } catch (e: AddressFormatException) {
                SegwitAddress.fromBech32(params, addressCandidate)
            }
        }
    }
}
