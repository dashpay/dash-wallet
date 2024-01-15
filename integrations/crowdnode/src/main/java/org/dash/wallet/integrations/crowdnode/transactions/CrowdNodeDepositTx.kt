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

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class CrowdNodeDepositTx(private val accountAddress: Address) : TransactionFilter {
    override fun matches(tx: Transaction): Boolean {
        val networkParams = accountAddress.parameters
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkParams)

        val allFromAccount = tx.inputs.all {
            val script = it.outpoint.connectedOutput?.scriptPubKey

            script != null &&
                (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                script.getToAddress(networkParams) == accountAddress
        }

        if (!allFromAccount) {
            return false
        }

        for (output in tx.outputs) {
            val script = output.scriptPubKey

            if ((ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                script.getToAddress(networkParams) == crowdNodeAddress
            ) {
                return !isApiRequest(output.value)
            }
        }

        return false
    }

    private fun isApiRequest(coin: Coin): Boolean {
        val toCheck = (coin - CrowdNodeConstants.API_OFFSET).value

        return toCheck <= ApiCode.MaxCode.code
    }
}
