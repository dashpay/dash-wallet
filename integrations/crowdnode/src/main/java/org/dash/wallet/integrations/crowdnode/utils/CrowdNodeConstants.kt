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
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params

object CrowdNodeConstants {
    val MINIMUM_REQUIRED_DASH: Coin = Coin.valueOf(1000000)
    val CROWDNODE_OFFSET: Coin = Coin.valueOf(20000)
    val CROWDNODE_ADDRESS: Address = Address.fromBase58(
        Context.get().params,
        if (Context.get().params == TestNet3Params.get()) {
            "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"
        } else {
            "XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2"
        }
    )
}