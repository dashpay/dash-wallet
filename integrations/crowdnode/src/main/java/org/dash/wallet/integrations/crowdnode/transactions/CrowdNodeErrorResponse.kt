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

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.CoinsFromAddressTxFilter
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class CrowdNodeErrorResponse(
    networkId: String,
    private val requestValue: Dash
) : CoinsFromAddressTxFilter(
    CrowdNodeConstants.getCrowdNodeAddress(networkId),
    requestValue,
    includeFee = true
) {
    private val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)

    override fun matches(tx: TxInfo): Boolean {
        return super.matches(tx) || isChangeSentBackToCrowdNode(tx)
    }

    private fun isChangeSentBackToCrowdNode(tx: TxInfo): Boolean {
        return tx.outputs.size > 2 &&
            tx.outputs.first().valueDuffs + (tx.feeDuffs ?: 0L) == requestValue.duffs &&
            tx.outputs.drop(1).any { it.address != null && it.address == crowdNodeAddress }
    }
}
