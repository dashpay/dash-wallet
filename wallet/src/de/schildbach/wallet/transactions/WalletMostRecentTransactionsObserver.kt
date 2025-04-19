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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet

class WalletMostRecentTransactionsObserver(private val wallet: Wallet) {
    private val workerJob = SupervisorJob()
    private val workerScope = CoroutineScope(Dispatchers.IO + workerJob)
    fun observe(): Flow<Transaction> = callbackFlow {
        fun emitMostRecentTransaction() {
            workerScope.launch {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                val allTxs = wallet.walletTransactions
                if (allTxs.any()) {
                    var mostRecentTx = allTxs.first()
                    allTxs.forEach {
                        if (it.transaction.updateTime > mostRecentTx.transaction.updateTime) {
                            mostRecentTx = it
                        }
                    }
                    trySend(mostRecentTx.transaction)
                }
            }
        }

        fun emitTransaction(transaction: Transaction) {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
            trySend(transaction)
        }

        val walletChangeListener = object : ThrottlingWalletChangeListener() {
            override fun onThrottledWalletChanged() {
                emitMostRecentTransaction()
            }

            override fun onCoinsReceived(
                wallet: Wallet?,
                tx: Transaction?,
                prevBalance: Coin?,
                newBalance: Coin?
            ) {
                super.onCoinsReceived(wallet, tx, prevBalance, newBalance)
                emitTransaction(tx!!)
            }

            override fun onCoinsSent(
                wallet: Wallet?,
                tx: Transaction?,
                prevBalance: Coin?,
                newBalance: Coin?
            ) {
                super.onCoinsSent(wallet, tx, prevBalance, newBalance)
                emitTransaction(tx!!)
            }
        }

        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)
        emitMostRecentTransaction()

        awaitClose {
            wallet.removeChangeEventListener(walletChangeListener)
            wallet.removeCoinsSentEventListener(walletChangeListener)
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            walletChangeListener.removeCallbacks()
            workerJob.cancel()
        }
    }
}