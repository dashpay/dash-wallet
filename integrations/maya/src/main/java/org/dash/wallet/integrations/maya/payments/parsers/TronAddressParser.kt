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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dash.wallet.integrations.maya.payments.parsers

import org.dash.wallet.common.payments.parsers.Base58
import org.dash.wallet.common.payments.parsers.AddressParser

/**
 * TRON address parser — Base58Check, 34 characters, leading `T`. Beyond the shape check, the
 * 4-byte double-SHA256 checksum and the 0x41 mainnet version byte are verified, so a
 * single-character typo is rejected client-side.
 */
class TronAddressParser : AddressParser("T[1-9A-HJ-NP-Za-km-z]{33}", null) {
    override fun verifyAddress(addressCandidate: String) {
        // Throws AddressFormatException on a bad checksum; returns version byte + payload.
        val decoded = Base58.decodeChecked(addressCandidate)
        require(decoded.size == 21 && decoded[0] == 0x41.toByte()) {
            "not a TRON mainnet address"
        }
    }
}
