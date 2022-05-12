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
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.transactions.TransactionFilter
import org.dash.wallet.common.transactions.TransactionWrapper

class FullCrowdNodeSignUpTxSet(networkParams: NetworkParameters): TransactionWrapper {
    private val crowdNodeTxFilters = listOf(
        CrowdNodeSignUpTx(networkParams),
        CrowdNodeAcceptTermsResponse(networkParams),
        CrowdNodeAcceptTermsTx(networkParams),
        CrowdNodeWelcomeToApiResponse(networkParams)
    )

    private val matchedFilters = mutableListOf<TransactionFilter>()
    override val transactions = mutableSetOf<Transaction>()

    val hasAcceptTermsResponse: Boolean
        get() = matchedFilters.any { it is CrowdNodeAcceptTermsResponse }

    val hasWelcomeToApiResponse: Boolean
        get() = matchedFilters.any { it is CrowdNodeWelcomeToApiResponse }

    val accountAddress: Address?
        get() = (matchedFilters.firstOrNull { it is CrowdNodeSignUpTx } as? CrowdNodeSignUpTx)?.fromAddresses?.first()

    override fun tryInclude(tx: Transaction): Boolean {
        val matchedFilter = crowdNodeTxFilters.firstOrNull { it.matches(tx) }

        if (matchedFilter != null) {
            transactions.add(tx)
            matchedFilters.add(matchedFilter)
            return true
        }

        return false
    }
}