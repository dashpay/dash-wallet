/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay

import android.text.format.DateUtils
import androidx.lifecycle.LiveData
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.utils.Threading
import java.util.*

class TransactionsLiveData(val walletApplication: WalletApplication = WalletApplication.getInstance()) : LiveData<List<Transaction>>() {

    private val wallet = walletApplication.wallet!!

    companion object {
        private const val THROTTLE_MS = DateUtils.SECOND_IN_MILLIS

        private val TRANSACTION_COMPARATOR: Comparator<Transaction> = object : Comparator<Transaction> {

            override fun compare(tx1: Transaction, tx2: Transaction): Int {
                val pending1 = tx1.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING
                val pending2 = tx2.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING
                if (pending1 != pending2) return if (pending1) -1 else 1
                val updateTime1 = tx1.updateTime
                val time1 = updateTime1?.time ?: 0
                val updateTime2 = tx2.updateTime
                val time2 = updateTime2?.time ?: 0
                return if (time1 != time2) if (time1 > time2) -1 else 1 else tx1.txId.compareTo(tx2.txId)
            }
        }
    }

    private val transactionChangeListener = object : ThrottlingWalletChangeListener(THROTTLE_MS) {
        override fun onThrottledWalletChanged() {
            loadData()
        }
    }

    private fun loadData() {
        Context.propagate(Constants.CONTEXT)

        val transactionsSet = wallet.getTransactions(true)
        val transactions: MutableList<Transaction> = ArrayList(transactionsSet.size)

        transactions.addAll(transactionsSet)
        Collections.sort(transactions, TRANSACTION_COMPARATOR)

        value = transactions
    }

    private var listening = false

    override fun onActive() {
        maybeAddEventsListener()
        loadData()
    }

    override fun onInactive() {
        maybeRemoveEventsListener()
    }

    private fun maybeAddEventsListener() {
        if (!listening && hasActiveObservers()) {
            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, transactionChangeListener)
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, transactionChangeListener)
            wallet.addChangeEventListener(Threading.SAME_THREAD, transactionChangeListener)
            wallet.addTransactionConfidenceEventListener(Threading.SAME_THREAD, transactionChangeListener)
            listening = true
        }
    }

    private fun maybeRemoveEventsListener() {
        if (listening) {
            wallet.removeTransactionConfidenceEventListener(transactionChangeListener)
            wallet.removeChangeEventListener(transactionChangeListener)
            wallet.removeCoinsSentEventListener(transactionChangeListener)
            wallet.removeCoinsReceivedEventListener(transactionChangeListener)
            transactionChangeListener.removeCallbacks()
            listening = false
        }
    }
}