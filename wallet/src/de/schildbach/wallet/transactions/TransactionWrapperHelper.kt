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
import org.slf4j.LoggerFactory

object TransactionWrapperHelper {
    private val log = LoggerFactory.getLogger(TransactionWrapperHelper::class.java)
    fun wrapTransactions(
        transactions: Set<Transaction?>,
        vararg wrappers: TransactionWrapper
    ): Collection<TransactionWrapper> {
        val wrappedTransactions = ArrayList<TransactionWrapper>()

        for (transaction in transactions) {
            log.info("wrapper ------------------ \n  {}", transaction?.txId)
            if (transaction == null) {
                continue
            }

            val anonWrapper: TransactionWrapper = object : TransactionWrapper {
                override val transactions = setOf(transaction)
                override fun tryInclude(tx: Transaction) = true
                override fun getValue(bag: TransactionBag) = transaction.getValue(bag)
            }

            if (wrappers.isNotEmpty()) {
                var added = false
                for (wrapper in wrappers) {
                    if (wrapper.tryInclude(transaction)) {
                        if (!wrappedTransactions.contains(wrapper)) {
                            //log.info("wrapper: {} -> added {}", wrapper::class.java.simpleName, transaction.txId)
                            wrappedTransactions.add(wrapper)
                        }
                        added = true
                    }
                }
                if (!added) {
                    wrappedTransactions.add(anonWrapper)
                    //log.info("wrapper a: {} -> added {}", "anonWrapper", transaction.txId)
                }
            } else {
                wrappedTransactions.add(anonWrapper)
                //log.info("wrapper b: {} -> added {}", "anonWrapper", transaction.txId)
            }
        }

        return wrappedTransactions
    }
}