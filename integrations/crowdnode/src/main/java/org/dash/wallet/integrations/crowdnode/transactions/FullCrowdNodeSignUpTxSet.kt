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
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.filters.TransactionFilter
import java.time.LocalDate
import java.time.ZoneId

open class FullCrowdNodeSignUpTxSet(
    networkParams: NetworkParameters,
    private val bag: TransactionBag
) : TransactionWrapper {
    private val signUpFilter = CrowdNodeSignUpTx(networkParams)
    private val crowdNodeTxFilters = mutableListOf(
        signUpFilter,
        CrowdNodeAcceptTermsResponse(networkParams),
        CrowdNodeAcceptTermsTx(networkParams),
        CrowdNodeWelcomeToApiResponse(networkParams),
        PossibleAcceptTermsResponse(bag, null),
        PossibleWelcomeResponse(bag, null)
    )

    private val matchedFilters = mutableListOf<TransactionFilter>()

    override val id: String = "crowdnode"
    override val transactions = hashMapOf<Sha256Hash, Transaction>()
    final override var groupDate: LocalDate = LocalDate.now()
        private set

    val isComplete: Boolean
        get() = transactions.count() == 5

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

    override fun tryInclude(tx: Transaction): Boolean {
        if (transactions.containsKey(tx.txId)) {
            transactions[tx.txId] = tx
            return true
        }

        if (tx.isEntirelySelf(bag)) {
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
            if (transactions.isEmpty()) {
                groupDate = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            }

            transactions[tx.txId] = tx
            matchedFilters.add(matchedFilter)
            return true
        }

        return false
    }

    override fun getValue(bag: TransactionBag): Coin {
        var result = Coin.ZERO

        for (pair in transactions) {
            val value = pair.value.getValue(bag)
            result = result.add(value)
        }

        return result
    }

    private fun didSignUpFromAddress(toAddress: Address?): Boolean {
        if (toAddress == null) {
            return false
        }

        val signUpTxs = matchedFilters.filterIsInstance<CrowdNodeSignUpTx>()
        return signUpTxs.any { it.fromAddresses.first() == toAddress }
    }
}
