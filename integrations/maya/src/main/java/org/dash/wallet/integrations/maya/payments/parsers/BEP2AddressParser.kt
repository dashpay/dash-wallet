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

package org.dash.wallet.integrations.maya.payments.parsers

import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Base58
import org.dash.wallet.common.payments.parsers.AddressParser

open class BEP2AddressParser(val prefix: String) : AddressParser(
    "[$prefix${Base58.ALPHABET.joinToString(separator = "")}]{24}",
    null
) {
    override fun verifyAddress(address: String) {
        if (!address.startsWith(prefix)) {
            throw AddressFormatException.InvalidPrefix("$prefix is not allowed for BEP2 $prefix addres")
        }
        if (address.length != 42) {
            throw AddressFormatException.InvalidDataLength(
                "length is incorrect for BEP2 $prefix address, ${address.length} != 42"
            )
        }

        try {
            val decoded = Base58.decode(address.substring(4))
            if (decoded.size != 24) {
                throw AddressFormatException.InvalidDataLength(
                    "length is invalid after decoding base58 for BEP2 $prefix addres, ${decoded.size} != 24"
                )
            }
        } catch (e: Exception) {
            throw AddressFormatException("BEP2 $prefix address is invalid: ${e.message}")
        }
    }
}
