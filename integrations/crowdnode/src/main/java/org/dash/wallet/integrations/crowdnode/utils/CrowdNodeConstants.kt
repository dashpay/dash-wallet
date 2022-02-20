/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.utils

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams

object CrowdNodeConstants {
    private const val CROWDNODE_TESTNET_ADDRESS = "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"
    private const val CROWDNODE_MAINNET_ADDRESS = "XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2"

    val MINIMUM_REQUIRED_DASH: Coin = Coin.valueOf(1000000)
    val REQUIRED_FOR_SIGNUP: Coin = MINIMUM_REQUIRED_DASH - Coin.valueOf(100000)
    val CROWDNODE_OFFSET: Coin = Coin.valueOf(20000)

    fun getCrowdNodeAddress(params: NetworkParameters): Address {
        return Address.fromBase58(params, if (params == MainNetParams.get()) {
            CROWDNODE_MAINNET_ADDRESS
        } else {
            CROWDNODE_TESTNET_ADDRESS
        })
    }
}