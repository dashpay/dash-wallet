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

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CoinJoinTxWrapperFactory(val params: NetworkParameters, val wallet: WalletEx) : TransactionWrapperFactory {
    private val wrapperMap = hashMapOf<Long, CoinJoinMixingTxSet>()
    override val wrappers: List<TransactionWrapper>
        get() = wrapperMap.values.toList()

    override fun tryInclude(tx: Transaction): Pair<Boolean, TransactionWrapper?> {
        return when (CoinJoinTransactionType.fromTx(tx, wallet)) {
            CoinJoinTransactionType.None, CoinJoinTransactionType.Send -> { Pair(false, null) }
            else -> {
                val instant = Instant.ofEpochMilli(tx.updateTime.time)
                val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                val startOfDay = localDateTime.toLocalDate().atStartOfDay(ZoneId.systemDefault())
                val startOfDayTimestamp = startOfDay.toInstant().toEpochMilli()
                val wrapper = wrapperMap[startOfDayTimestamp]
                if (wrapper != null) {
                    Pair(wrapper.tryInclude(tx), wrapper)
                } else {
                    val newWrapper = CoinJoinMixingTxSet(params, wallet)
                    val included = newWrapper.tryInclude(tx)
                    wrapperMap[startOfDayTimestamp] = newWrapper
                    Pair(included, newWrapper)
                }
            }
        }
    }
}
