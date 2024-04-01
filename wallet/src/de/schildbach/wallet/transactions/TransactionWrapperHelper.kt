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

package de.schildbach.wallet.transactions

import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.slf4j.LoggerFactory

object TransactionWrapperHelper {
    private val log = LoggerFactory.getLogger(TransactionWrapperHelper::class.java)
    fun wrapTransactions(
        transactions: Set<Transaction?>,
        vararg wrapperFactories: TransactionWrapperFactory
    ): Collection<TransactionWrapper> {
        val wrappedTransactions = ArrayList<TransactionWrapper>()
        for (transaction in transactions) {
            if (transaction == null) {
                continue
            }

            val anonWrapper: TransactionWrapper = object : TransactionWrapper {
                override val transactions = setOf(transaction)
                override fun tryInclude(tx: Transaction) = true
                override fun getValue(bag: TransactionBag) = transaction.getValue(bag)
            }

            if (wrapperFactories.isNotEmpty()) {
                var added = false
                for (wrapperFactory in wrapperFactories) {
                    val (included, wrapper) = wrapperFactory.tryInclude(transaction)
                    if (included && wrapper != null) {
                        if (!wrappedTransactions.contains(wrapper)) {
                            wrappedTransactions.add(wrapper)
                        }
                        added = true
                    }
                }
                if (!added) {
                    wrappedTransactions.add(anonWrapper)
                }
            } else {
                wrappedTransactions.add(anonWrapper)
            }
        }

        return wrappedTransactions
    }
}
