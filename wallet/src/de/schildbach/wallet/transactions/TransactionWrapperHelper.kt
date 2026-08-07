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

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.dash.wallet.common.transactions.TxInfo

object TransactionWrapperHelper {
    fun wrapTransactions(
        transactions: Set<Transaction?>,
        bag: TransactionBag,
        params: NetworkParameters,
        vararg wrapperFactories: TransactionWrapperFactory
    ): Collection<TransactionWrapper> {
        wrapperFactories.sortByDescending { it.averageTransactions }
        val wrappedTransactions = ArrayList<TransactionWrapper>()

        for (transaction in transactions) {
            if (transaction == null) {
                continue
            }

            val txInfo = transaction.toTxInfo(bag, params)
            var added = false

            for (wrapperFactory in wrapperFactories) {
                val (included, wrapper) = wrapperFactory.tryInclude(txInfo)
                if (included && wrapper != null) {
                    if (!wrappedTransactions.contains(wrapper)) {
                        wrappedTransactions.add(wrapper)
                    }
                    added = true
                    break
                }
            }

            if (!added) {
                val anonWrapper: TransactionWrapper = object : TransactionWrapper {
                    override val id: String = transaction.txId.toStringBase58()
                    override val transactions = hashMapOf(txInfo.txId to txInfo)
                    override val groupDate = txInfo.groupDate
                    override fun tryInclude(tx: TxInfo) = true
                    override fun getValue() = Dash(txInfo.netValueDuffs)
                }
                wrappedTransactions.add(anonWrapper)
            }
        }

        return wrappedTransactions
    }
}
