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

import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class CrowdNodeDepositTx(private val accountAddress: String) : TransactionFilter {
    override fun matches(tx: TxInfo): Boolean {
        val networkId = AddressNetwork.fromDashAddress(accountAddress).id
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)

        val allFromAccount = tx.inputs.all {
            it.connectedAddress != null && it.connectedAddress == accountAddress
        }

        if (!allFromAccount) {
            return false
        }

        for (output in tx.outputs) {
            if (output.address != null && output.address == crowdNodeAddress) {
                return !isApiRequest(output.valueDuffs)
            }
        }

        return false
    }

    private fun isApiRequest(valueDuffs: Long): Boolean {
        val toCheck = valueDuffs - CrowdNodeConstants.API_OFFSET.duffs

        return toCheck <= ApiCode.MaxCode.code
    }
}
