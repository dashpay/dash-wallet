/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.transactions.coinjoin

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import java.time.LocalDate
import java.time.ZoneId

class CoinJoinTxWrapperFactory(val params: NetworkParameters, val wallet: WalletEx) : TransactionWrapperFactory {
    private val wrapperMap = hashMapOf<LocalDate, CoinJoinMixingTxSet>()
    override val wrappers: List<TransactionWrapper>
        get() = wrapperMap.values.toList()
    override val averageTransactions: Long = Long.MAX_VALUE

    override fun tryInclude(tx: Transaction): Pair<Boolean, TransactionWrapper?> {
        val localDate = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        val wrapper = wrapperMap[localDate]

        return if (wrapper != null) {
            Pair(wrapper.tryInclude(tx), wrapper)
        } else {
            val newWrapper = CoinJoinMixingTxSet(wallet)
            val included = newWrapper.tryInclude(tx)

            if (included) {
                wrapperMap[localDate] = newWrapper
                Pair(true, newWrapper)
            } else {
                Pair(false, null)
            }
        }
    }

    fun forceInclude(tx: Transaction) {
        val localDate = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        val wrapper = wrapperMap[localDate]
        wrapper?.transactions?.set(tx.txId, tx)
    }
}
