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
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.listeners.TransactionConfidenceEventListener
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.WalletChangeEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.slf4j.LoggerFactory

class WalletObserver(private val wallet: Wallet) {
    companion object {
        val log = LoggerFactory.getLogger(WalletObserver::class.java)
    }
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
        log.info("observing transactions start {}", this@WalletObserver)
        try {
            Threading.USER_THREAD.execute {
                try {
                    Context.propagate(wallet.context)
                    if (Looper.myLooper() == null) {
                        Looper.prepare()
                    }
                } catch (e: Exception) {
                    log.error("Error during threading setup", e)
                    close(e) // Propagate error to Flow
                }
            }

            val coinsSentListener = WalletCoinsSentEventListener { _, tx: Transaction?, _, _ ->
                try {
                    if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                        log.info("observing transaction sent: {} [=====] {}", tx.txId, this@WalletObserver)
                        trySend(tx).onFailure {
                            log.error("Failed to send transaction sent event", it)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error in coinsSentListener", e)
                    close(e)
                }
            }

            val coinsReceivedListener = WalletCoinsReceivedEventListener { _, tx: Transaction?, _, _ ->
                try {
                    if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                        log.info("observing transaction received: {} [=====] {}", tx.txId, this@WalletObserver)
                        trySend(tx).onFailure {
                            log.error("Failed to send transaction received event", it)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error in coinsReceivedListener", e)
                    close(e)
                }
            }

            var transactionConfidenceChangedListener: TransactionConfidenceEventListener? = null

            if (observeTxConfidence) {
                transactionConfidenceChangedListener = TransactionConfidenceEventListener { _, tx: Transaction? ->
                    try {
                        if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
                            log.info("observing transaction conf {} [=====] {}", tx.txId, this@WalletObserver)
                            if (tx.getConfidence(wallet.context).depthInBlocks < 7) {
                                trySend(tx).onFailure {
                                    log.error("Failed to send transaction confidence event", it)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.error("Error in transactionConfidenceChangedListener", e)
                        close(e)
                    }
                }
                wallet.addTransactionConfidenceEventListener(Threading.USER_THREAD, transactionConfidenceChangedListener)
            }

            wallet.addCoinsSentEventListener(Threading.USER_THREAD, coinsSentListener)
            wallet.addCoinsReceivedEventListener(Threading.USER_THREAD, coinsReceivedListener)

            awaitClose {
                log.info("observing transactions stop: {}", this@WalletObserver)
                wallet.removeCoinsSentEventListener(coinsSentListener)
                wallet.removeCoinsReceivedEventListener(coinsReceivedListener)
                if (observeTxConfidence) {
                    wallet.removeTransactionConfidenceEventListener(transactionConfidenceChangedListener)
                }
            }
        } catch (e: Exception) {
            log.error("Error setting up transaction observation", e)
            close(e)
        }
    }.catch { e ->
        log.error("observing transactions error", e)
    }
}
