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

import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.uri.BitcoinURIParseException

/**
 * Neutral (dashj-free) parsing of `bitcoin:` payment URIs for modules that must not import
 * bitcoinj. Delegates to [BitcoinURI] internally so accepted URIs are identical.
 */
object BitcoinUris {

    /**
     * Extracts the address from a mainnet `bitcoin:` URI.
     *
     * @throws IllegalArgumentException if the URI can't be parsed, carries no address,
     * or the address is not a mainnet Bitcoin address.
     */
    fun parseAddress(uri: String): String {
        val params = BitcoinMainNetParams()
        try {
            val bitcoinUri = BitcoinURI(params, uri)
            val address = bitcoinUri.address
                ?: throw IllegalArgumentException("no address in bitcoin uri")

            if (params != address.parameters) {
                throw IllegalArgumentException("mismatched network")
            }

            return address.toString()
        } catch (ex: BitcoinURIParseException) {
            throw IllegalArgumentException(ex.message, ex)
        }
    }
}
