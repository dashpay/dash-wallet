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
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.TransactionFilter
import java.time.LocalDate

open class FullCrowdNodeSignUpTxSet(
    networkId: String
) : TransactionWrapper {
    private val signUpFilter = CrowdNodeSignUpTx(networkId)
    private val crowdNodeTxFilters = mutableListOf<TransactionFilter>(
        signUpFilter,
        CrowdNodeAcceptTermsResponse(networkId),
        CrowdNodeAcceptTermsTx(networkId),
        CrowdNodeWelcomeToApiResponse(networkId),
        PossibleAcceptTermsResponse(null),
        PossibleWelcomeResponse(null)
    )

    private val matchedFilters = mutableListOf<TransactionFilter>()

    override val id: String = "crowdnode"
    override val transactions = hashMapOf<String, TxInfo>()
    final override var groupDate: LocalDate = LocalDate.now()
        private set

    val isComplete: Boolean
        get() = welcomeToApiResponse != null && transactions.count() >= 5

    open val acceptTermsResponse: CrowdNodeAcceptTermsResponse?
        get() = matchedFilters.filterIsInstance<CrowdNodeAcceptTermsResponse>().firstOrNull()

    open val possibleAcceptTermsResponse: PossibleAcceptTermsResponse?
        get() = matchedFilters.filterIsInstance<PossibleAcceptTermsResponse>().firstOrNull {
            didSignUpFromAddress(
                it.toAddress
            )
        }

    open val welcomeToApiResponse: CrowdNodeWelcomeToApiResponse?
        get() = matchedFilters.filterIsInstance<CrowdNodeWelcomeToApiResponse>().firstOrNull()

    open val possibleWelcomeToApiResponse: PossibleWelcomeResponse?
        get() = matchedFilters.filterIsInstance<PossibleWelcomeResponse>().firstOrNull {
            didSignUpFromAddress(
                it.toAddress
            )
        }

    override fun tryInclude(tx: TxInfo): Boolean {
        if (transactions.containsKey(tx.txId)) {
            transactions[tx.txId] = tx
            return true
        }

        if (tx.isEntirelySelf) {
            // We might not have our CrowdNode account address by the time the topUp
            // transaction is found, which means we need to check its `spentBy`
            for (output in tx.outputs) {
                output.spentBy?.let {
                    if (signUpFilter.matches(it)) {
                        val accountAddress = signUpFilter.fromAddresses.first()
                        crowdNodeTxFilters.add(CrowdNodeTopUpTx(accountAddress))
                    }
                }
            }
        }

        val matchedFilter = crowdNodeTxFilters.firstOrNull { it.matches(tx) }

        if (matchedFilter != null) {
            if (transactions.isEmpty()) {
                groupDate = tx.groupDate
            }

            transactions[tx.txId] = tx
            matchedFilters.add(matchedFilter)
            return true
        }

        return false
    }

    override fun getValue(): Dash {
        var result = Dash.ZERO

        for (pair in transactions) {
            val value = Dash.valueOf(pair.value.netValueDuffs)
            result = result.add(value)
        }

        return result
    }

    private fun didSignUpFromAddress(toAddress: String?): Boolean {
        if (toAddress == null) {
            return false
        }

        val signUpTxs = matchedFilters.filterIsInstance<CrowdNodeSignUpTx>()
        return signUpTxs.any { it.fromAddresses.first() == toAddress }
    }
}
