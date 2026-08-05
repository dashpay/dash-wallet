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

import org.bitcoinj.core.Base58
import org.dash.wallet.common.payments.parsers.AddressParser

/**
 * Solana address parser — Base58 ed25519 public key, 32 to 44 characters.
 */
class SolanaAddressParser : AddressParser("[1-9A-HJ-NP-Za-km-z]{32,44}", null) {
    /**
     * A Solana address is the Base58 encoding of a raw 32-byte ed25519 public key. The
     * lexical pattern alone also matches other chains' Base58 strings — notably a Dash
     * P2PKH address (25-byte Base58Check payload, 34 chars), which then only fails at
     * conversion time (MO-969) — so require the decoded payload to be exactly 32 bytes.
     */
    override fun verifyAddress(addressCandidate: String) {
        val decoded = Base58.decode(addressCandidate)
        require(decoded.size == 32) {
            "not a Base58-encoded 32-byte key (${decoded.size} bytes)"
        }
    }
}
