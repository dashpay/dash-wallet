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

import org.bitcoinj.core.Coin
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.BuildConfig

object Constants {
    val CROWD_NODE_ADDRESS = if (BuildConfig.DEBUG) { // TODO: network, not build type
        "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"
    } else {
        "XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2"
    }

    val NETWORK_PARAMETERS = TestNet3Params.get() // TODO
    val MINIMUM_REQUIRED_DASH: Coin = Coin.valueOf(1000000)
}