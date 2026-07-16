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

package org.dash.wallet.common.money

import org.bitcoinj.core.Address
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.NetworkParameters

/**
 * Network identifiers, decoupled from dashj's NetworkParameters constants
 * (the values are the same strings dashj uses).
 */
object DashNetworks {
    const val MAINNET = NetworkParameters.ID_MAINNET
    const val TESTNET = NetworkParameters.ID_TESTNET
}

/**
 * Base58 Dash address validation for modules that must not depend on dashj.
 * Delegates to dashj internally so accepted addresses are exactly those the wallet accepts.
 */
object DashAddressValidator {

    /** True if [address] parses as a Dash address on any network. */
    fun isValid(address: String): Boolean = networkIdOrNull(address) != null

    /** True if [address] parses as a Dash address on the network identified by [networkId] (see [DashNetworks]). */
    fun isValid(address: String, networkId: String): Boolean = networkIdOrNull(address) == networkId

    /** The network id of [address] (see [DashNetworks]), or null if it is not a valid address. */
    fun networkIdOrNull(address: String): String? {
        return try {
            Address.getParametersFromAddress(address).id
        } catch (e: AddressFormatException) {
            null
        }
    }
}
