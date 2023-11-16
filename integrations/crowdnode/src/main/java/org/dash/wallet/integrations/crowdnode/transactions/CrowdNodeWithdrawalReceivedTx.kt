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

package org.dash.wallet.integrations.crowdnode.transactions

import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class CrowdNodeWithdrawalReceivedTx(
    private val networkParams: NetworkParameters
) : TransactionFilter {
    private val joinedFilters = mutableListOf<TransactionFilter>()

    override fun matches(tx: Transaction): Boolean {
        if (joinedFilters.any { !it.matches(tx) }) {
            return false
        }

        val fromAddress = CrowdNodeConstants.getCrowdNodeAddress(networkParams)

        for (input in tx.inputs) {
            input.outpoint.connectedOutput?.let { connectedOutput ->
                val script = connectedOutput.scriptPubKey

                if ((ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                    script.getToAddress(networkParams) == fromAddress
                ) {
                    return !tx.outputs.any { isApiResponse(it.value) }
                }
            }
        }

        return false
    }

    fun and(txFilter: TransactionFilter): CrowdNodeWithdrawalReceivedTx {
        joinedFilters.add(txFilter)
        return this
    }

    private fun isApiResponse(coin: Coin): Boolean {
        val toCheck = (coin - CrowdNodeConstants.API_OFFSET).value

        return toCheck in 1..1024 || (toCheck <= ApiCode.MaxCode.code && isPowerOfTwo(coin.value))
    }

    private fun isPowerOfTwo(number: Long): Boolean {
        return number and number - 1 == 0L
    }
}
