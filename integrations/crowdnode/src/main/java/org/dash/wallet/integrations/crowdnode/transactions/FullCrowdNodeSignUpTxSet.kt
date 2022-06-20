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

import org.bitcoinj.core.*
import org.dash.wallet.common.transactions.TransactionComparator
import org.dash.wallet.common.transactions.TransactionFilter
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionWrapper

open class FullCrowdNodeSignUpTxSet(
    networkParams: NetworkParameters,
    private val bag: TransactionBag
): TransactionWrapper {
    private val signUpFilter = CrowdNodeSignUpTx(networkParams)
    private val crowdNodeTxFilters = mutableListOf(
        signUpFilter,
        CrowdNodeAcceptTermsResponse(networkParams),
        CrowdNodeAcceptTermsTx(networkParams),
        CrowdNodeWelcomeToApiResponse(networkParams)
    )

    private val matchedFilters = mutableListOf<TransactionFilter>()
    override val transactions = sortedSetOf(TransactionComparator())

    open val hasAcceptTermsResponse: Boolean
        get() = matchedFilters.any { it is CrowdNodeAcceptTermsResponse }

    open val hasWelcomeToApiResponse: Boolean
        get() = matchedFilters.any { it is CrowdNodeWelcomeToApiResponse }

    open val accountAddress: Address?
        get() = (matchedFilters.firstOrNull { it is CrowdNodeSignUpTx } as? CrowdNodeSignUpTx)?.fromAddresses?.first()

    override fun tryInclude(tx: Transaction): Boolean {
        if (transactions.any { it.txId == tx.txId }) {
            return false
        }

        if (TransactionUtils.isEntirelySelf(tx, bag)) {
            // We might not have our CrowdNode account address by the time the topUp
            // transaction is found, which means we need to check its `spentBy`
            for (output in tx.outputs) {
                output.spentBy?.let {
                    if (signUpFilter.matches(it.parentTransaction)) {
                        val accountAddress = signUpFilter.fromAddresses.first()
                        crowdNodeTxFilters.add(CrowdNodeTopUpTx(accountAddress, bag))
                    }
                }
            }
        }

        val matchedFilter = crowdNodeTxFilters.firstOrNull { it.matches(tx) }

        if (matchedFilter != null) {
            transactions.add(tx)
            matchedFilters.add(matchedFilter)
            return true
        }

        return false
    }

    override fun getValue(bag: TransactionBag): Coin {
        var result = Coin.ZERO

        for (tx in transactions) {
            val value = tx.getValue(bag)
            val isSent = value.signum() < 0
            val fee = tx.fee
            val addFee = isSent && fee != null && !fee.isZero

            result = result.add(if (addFee) value.minus(fee) else value)
        }

        return result
    }
}