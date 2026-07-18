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
 * Parsing of `bitcoin:` payment URIs. Same accepted URIs as dashj's `BitcoinURI` with
 * mainnet Bitcoin parameters.
 */
object BitcoinUris {

    /**
     * Extracts the address from a mainnet `bitcoin:` URI.
     *
     * @throws IllegalArgumentException if the URI can't be parsed, carries no address,
     * or the address is not a mainnet Bitcoin address.
     */
    fun parseAddress(uri: String): String {
        val params = AddressNetwork.BITCOIN_MAINNET
        try {
            val bitcoinUri = PaymentURI(params, uri)
            return bitcoinUri.address
                ?: throw IllegalArgumentException("no address in bitcoin uri")
        } catch (ex: PaymentURI.ParseException) {
            throw IllegalArgumentException(ex.message, ex)
        }
    }
}
