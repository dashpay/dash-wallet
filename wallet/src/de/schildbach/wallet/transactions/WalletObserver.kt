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

import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.listeners.TransactionConfidenceEventListener
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.WalletChangeEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener
import org.dash.wallet.common.transactions.filters.TransactionFilter

class WalletObserver(private val wallet: Wallet) {
    fun observeWalletChanged(): Flow<Unit> = callbackFlow {
        val walletChangeListener = WalletChangeEventListener {
            trySend(Unit)
        }

        wallet.addChangeEventListener(Threading.USER_THREAD, walletChangeListener)

        awaitClose {
            wallet.removeChangeEventListener(walletChangeListener)
        }
    }

    fun observeTransactions(
        observeTxConfidence: Boolean,
        vararg filters: TransactionFilter
    ): Flow<Transaction> = callbackFlow {
        Threading.USER_THREAD.execute {
            Context.propagate(wallet.context)

            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
        }

        val coinsSentListener = WalletCoinsSentEventListener { _, tx: Transaction?, _, _ ->
            if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                trySend(tx)
            }
        }
        wallet.addCoinsSentEventListener(Threading.USER_THREAD, coinsSentListener)

        val coinsReceivedListener = WalletCoinsReceivedEventListener { _, tx: Transaction?, _, _ ->
            if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                trySend(tx)
            }
        }
        wallet.addCoinsReceivedEventListener(Threading.USER_THREAD, coinsReceivedListener)

        var transactionConfidenceChangedListener: TransactionConfidenceEventListener? = null

        if (observeTxConfidence) {
            transactionConfidenceChangedListener = TransactionConfidenceEventListener { _, tx: Transaction? ->
                if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                    trySend(tx)
                }
            }
            wallet.addTransactionConfidenceEventListener(Threading.USER_THREAD, transactionConfidenceChangedListener)
        }

        awaitClose {
            wallet.removeCoinsSentEventListener(coinsSentListener)
            wallet.removeCoinsReceivedEventListener(coinsReceivedListener)

            if (observeTxConfidence) {
                wallet.removeTransactionConfidenceEventListener(transactionConfidenceChangedListener)
            }
        }
    }
}
