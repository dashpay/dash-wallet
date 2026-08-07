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

import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.integrations.maya.payments.AddressGenerator

/**
 * XRP classic address parser — Base58Check in the ripple alphabet, leading `r`, 25-35 chars.
 * Beyond the shape check, the 4-byte double-SHA256 checksum and the 0x00 account version byte
 * are verified, so a single-character typo is rejected client-side.
 */
class XrpAddressParser : AddressParser("r[1-9A-HJ-NP-Za-km-z]{24,34}", null) {
    override fun verifyAddress(addressCandidate: String) {
        val data = AddressGenerator.rippleBase58Decode(addressCandidate)
        // Version byte (0x00 = account id) + 20-byte account id + 4-byte checksum.
        require(data.size == 25) { "XRP address must decode to 25 bytes" }
        require(data[0] == 0.toByte()) { "not an XRP account-id version byte" }
        val checksum = AddressGenerator.sha256Twice(data.copyOfRange(0, 21)).copyOfRange(0, 4)
        require(checksum.contentEquals(data.copyOfRange(21, 25))) { "invalid XRP address checksum" }
    }
}
