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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.listeners.TransactionConfidenceEventListener
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.WalletChangeEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener
import org.bitcoinj.wallet.listeners.WalletResetEventListener
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    fun observeWalletReset(): Flow<Unit> = callbackFlow {
        val walletChangeListener = WalletResetEventListener {
            trySend(Unit)
        }

        wallet.addResetEventListener(Threading.USER_THREAD, walletChangeListener)

        awaitClose {
            wallet.removeResetEventListener(walletChangeListener)
        }
    }

    /** observe new transactions (sent and received) and optionally transaction confidence changes for the past hour. */
    fun observeTransactions(
        observeTxConfidence: Boolean,
        vararg filters: TransactionFilter
    ): Flow<Transaction> = callbackFlow {
        log.info("PERF: observing transactions start {}", this@WalletObserver)
        try {
            Context.propagate(wallet.context)
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
            val transactions = ConcurrentHashMap<Sha256Hash, Transaction>()
//            Threading.USER_THREAD.execute {
//                try {
//                    Context.propagate(wallet.context)
//                    if (Looper.myLooper() == null) {
//                        Looper.prepare()
//                    }
//                } catch (e: Exception) {
//                    log.error("PERF: Error during threading setup", e)
//                    close(e) // Propagate error to Flow
//                }
//            }

            var transactionConfidenceListener: TransactionConfidence.Listener? = null

            val coinsSentListener = WalletCoinsSentEventListener { _, tx: Transaction?, _, _ ->
                Context.propagate(wallet.context)
                try {
                    val oneHourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
                    if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
//                         log.info("PERF: observing transaction sent: {} [=====] {}", tx.txId, this@WalletObserver)
                        if (tx.updateTime.time > oneHourAgo && observeTxConfidence) {
                            transactions[tx.txId] = tx
                            tx.confidence.addEventListener(Threading.USER_THREAD, transactionConfidenceListener)
                            // log.info("observing transaction: start listening to {}", tx.txId)
                        }
                        trySend(tx).onFailure {
                            log.error("PERF: Failed to send transaction sent event", it)
                        }
                    }
                } catch (e: Exception) {
                    log.error("PERF: Error in coinsSentListener", e)
                    close(e)
                }
            }

            val coinsReceivedListener = WalletCoinsReceivedEventListener { _, tx: Transaction?, _, _ ->
                Context.propagate(wallet.context)
                try {
                    if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
//                         log.info("observing transaction received: {} [=====] {}", tx.txId, this@WalletObserver)
                        val oneHourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
                        if (tx.updateTime.time > oneHourAgo && observeTxConfidence) {
                            transactions[tx.txId] = tx
                            tx.confidence.addEventListener(Threading.USER_THREAD, transactionConfidenceListener)
                            // log.info("observing transaction: start listening to {}", tx.txId)
                        }
                        trySend(tx).onFailure {
                            log.error("PERF: Failed to send transaction received event", it)
                        }
                    }
                } catch (e: Exception) {
                    log.error("PERF: Error in coinsReceivedListener", e)
                    close(e)
                }
            }

            if (observeTxConfidence) {
                transactionConfidenceListener = TransactionConfidence.Listener { transactionConfidence, changeReason ->
                    try {
                        val tx = transactions[transactionConfidence.transactionHash]
                        if (tx != null && (filters.isEmpty() || filters.any { it.matches(tx) })) {
//                             log.info("observing transaction conf {} [=====] {}", tx.txId, this@WalletObserver)
                            trySend(tx).onFailure {
                                log.error("PERF: Failed to send transaction confidence event", it)
                            }
                        }
                        val shouldStopListening = when (changeReason) {
                            TransactionConfidence.Listener.ChangeReason.CHAIN_LOCKED -> transactionConfidence.isChainLocked
                            TransactionConfidence.Listener.ChangeReason.IX_TYPE -> transactionConfidence.isTransactionLocked
                            TransactionConfidence.Listener.ChangeReason.DEPTH -> transactionConfidence.depthInBlocks >= 6
                            else -> false
                        }
                        if (shouldStopListening) {
                            // log.info("observing transaction: stop listening to {}", transactionConfidence.transactionHash)
                            transactionConfidence.removeEventListener(transactionConfidenceListener)
                            transactions.remove(transactionConfidence.transactionHash)
                        }
                    } catch (e: Exception) {
                        log.error("PERF: Error in transactionConfidenceChangedListener", e)
                        close(e)
                    }
                }
            }

            wallet.addCoinsSentEventListener(Threading.USER_THREAD, coinsSentListener)
            wallet.addCoinsReceivedEventListener(Threading.USER_THREAD, coinsReceivedListener)

            awaitClose {
                log.info("PERF: observing transactions stop: {}", this@WalletObserver)
                wallet.removeCoinsSentEventListener(coinsSentListener)
                wallet.removeCoinsReceivedEventListener(coinsReceivedListener)
                if (observeTxConfidence) {
                    transactions.forEach { (_, tx) ->
                        tx.confidence.removeEventListener(transactionConfidenceListener)
                        // log.info("observing transaction: stop listening to {}", tx.txId)
                    }
                    transactions.clear()
                }
            }
        } catch (e: Exception) {
            log.error("PERF: Error setting up transaction observation", e)
            close(e)
        }
    }
}
