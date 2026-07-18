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

import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class CrowdNodeWithdrawalReceivedTx(
    private val networkId: String
) : TransactionFilter {
    private val joinedFilters = mutableListOf<TransactionFilter>()

    override fun matches(tx: TxInfo): Boolean {
        if (joinedFilters.any { !it.matches(tx) }) {
            return false
        }

        val fromAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)

        for (input in tx.inputs) {
            if (input.connectedAddress != null && input.connectedAddress == fromAddress) {
                return !tx.outputs.any { isApiResponse(it.valueDuffs) }
            }
        }

        return false
    }

    fun and(txFilter: TransactionFilter): CrowdNodeWithdrawalReceivedTx {
        joinedFilters.add(txFilter)
        return this
    }

    private fun isApiResponse(valueDuffs: Long): Boolean {
        val toCheck = valueDuffs - CrowdNodeConstants.API_OFFSET.duffs

        return toCheck in 1..1024 || (toCheck <= ApiCode.MaxCode.code && isPowerOfTwo(valueDuffs))
    }

    private fun isPowerOfTwo(number: Long): Boolean {
        return number and number - 1 == 0L
    }
}
