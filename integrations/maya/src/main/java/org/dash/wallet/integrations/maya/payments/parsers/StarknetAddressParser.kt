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

/**
 * Starknet address parser — 0x followed by 50-64 hex characters (a 252-bit felt, with or
 * without leading-zero padding). Felts have no checksum, so length is the only structural
 * gate: short values like `0x0` or `0xdeadbeef` are syntactically legal felts but never
 * real account addresses (key-derived addresses have ~252 bits of entropy), and funds sent
 * to one are burned. The all-zero felt is rejected outright.
 */
class StarknetAddressParser : AddressParser("0x[a-fA-F0-9]{50,64}", null) {
    override fun verifyAddress(addressCandidate: String) {
        require(addressCandidate.drop(2).any { it != '0' }) {
            "the zero felt is not a valid Starknet address"
        }
    }
}
