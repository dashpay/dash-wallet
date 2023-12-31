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
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.filters.CoinsFromAddressTxFilter
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class CrowdNodeErrorResponse(
    private val networkParams: NetworkParameters,
    private val requestValue: Coin
) : CoinsFromAddressTxFilter(
    CrowdNodeConstants.getCrowdNodeAddress(networkParams),
    requestValue,
    includeFee = true
) {
    override fun matches(tx: Transaction): Boolean {
        return super.matches(tx) || isChangeSentBackToCrowdNode(tx)
    }

    private fun isChangeSentBackToCrowdNode(tx: Transaction): Boolean {
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkParams)
        return tx.outputs.size > 2 &&
            tx.outputs.first().value + (tx.fee ?: Coin.ZERO) == requestValue &&
            tx.outputs.drop(1).any { addressMatch(it.scriptPubKey, crowdNodeAddress) }
    }

    private fun addressMatch(script: Script, address: Address): Boolean {
        return (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
            script.getToAddress(address.parameters) == address
    }
}
