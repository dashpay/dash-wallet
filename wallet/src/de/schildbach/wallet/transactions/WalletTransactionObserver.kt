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
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.transactions.TransactionFilter

@ExperimentalCoroutinesApi
class WalletTransactionObserver(private val wallet: Wallet) {
    fun observe(vararg filters: TransactionFilter): Flow<Transaction> = callbackFlow {
        Context.propagate(wallet.context)

        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        val walletChangeListener = object : ThrottlingWalletChangeListener() {
            override fun onThrottledWalletChanged() { }

            override fun onCoinsReceived(
                wallet: Wallet?,
                tx: Transaction?,
                prevBalance: Coin?,
                newBalance: Coin?
            ) {
                super.onCoinsReceived(wallet, tx, prevBalance, newBalance)

                if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                    trySend(tx)
                }
            }

            override fun onCoinsSent(
                wallet: Wallet?,
                tx: Transaction?,
                prevBalance: Coin?,
                newBalance: Coin?
            ) {
                super.onCoinsSent(wallet, tx, prevBalance, newBalance)

                if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                    trySend(tx)
                }
            }

            override fun onTransactionConfidenceChanged(wallet: Wallet?, tx: Transaction?) {
                super.onTransactionConfidenceChanged(wallet, tx)

                if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                    trySend(tx)
                }
            }
        }

        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addTransactionConfidenceEventListener(Threading.SAME_THREAD, walletChangeListener)

        awaitClose {
            wallet.removeChangeEventListener(walletChangeListener)
            wallet.removeCoinsSentEventListener(walletChangeListener)
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            walletChangeListener.removeCallbacks()
        }
    }
}