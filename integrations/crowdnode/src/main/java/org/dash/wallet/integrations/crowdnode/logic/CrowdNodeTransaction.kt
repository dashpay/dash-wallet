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

package org.dash.wallet.integrations.crowdnode.logic

import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.transactions.TransactionFilter
import org.dash.wallet.integrations.crowdnode.utils.Constants

class CrowdNodeTransaction(private val accountAddress: Address): TransactionFilter {
    val crowdNodeAddress = Address.fromBase58(Constants.NETWORK_PARAMETERS, Constants.CROWD_NODE_ADDRESS)

    override fun matches(tx: Transaction): Boolean {
        val inputs = tx.inputs
        val outputs = tx.outputs

        if (inputs.isNotEmpty()) {
            for (input in inputs) {
                try {
                    val outpoint = input.outpoint
                    val connectedOutput = outpoint.connectedOutput

                    if (connectedOutput != null) {
                        val scriptPubKey = connectedOutput.scriptPubKey
                        val address = scriptPubKey.getToAddress(Constants.NETWORK_PARAMETERS)

                        if (address == crowdNodeAddress || address == accountAddress) {
                            return true
                        }
                    } else {
                        // TODO: unconnected
                    }
                } catch (e: Exception) {
                    // TODO: exception
                }
            }
        } else {
            // TODO: no inputs
        }

        for (out in outputs) {
            try {
                val scriptPubKey = out.scriptPubKey
                val address = scriptPubKey.getToAddress(Constants.NETWORK_PARAMETERS)

                if (address == crowdNodeAddress || address == accountAddress) {
                    return true
                }
            } catch (e: Exception) {
                // TODO: exception
            }
        }

        return false
    }
}