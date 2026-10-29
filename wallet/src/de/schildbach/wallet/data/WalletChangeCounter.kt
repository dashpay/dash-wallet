/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.data

import org.bitcoinj.core.listeners.TransactionConfidenceEventListener
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.WalletChangeEventListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Backs [org.dash.wallet.common.WalletDataProvider.walletChangeCount]: a process-local counter that
 * advances on every change to the wallet it is attached to and whenever it is attached to a
 * different wallet.
 *
 * It attaches lazily, on [count], to the wallet passed in, so it follows wallet replacement without
 * hooking every place a wallet is installed: a caller that reads [count] before and after an
 * operation sees a different value if the wallet was replaced in between, because the second read
 * attaches to the new instance. (A replaced wallet object is never installed again, so switching
 * back to the same instance between two reads isn't a case.)
 *
 * The listeners run on [Threading.SAME_THREAD], i.e. inline in the mutating thread while it still
 * holds the wallet lock, so a change is counted before any reader can take the wallet lock after
 * it. dashj 22.0.5 queues `onWalletChanged` before releasing the lock on every transaction-graph
 * change, except when a best-chain block delivers a transaction (`Wallet.receive`), where it is
 * deferred to the following `notifyNewBestBlock` in a separate lock acquisition; that path does
 * however notify the transaction confidence listeners inline for the received transaction, so
 * those are counted too. A reorganize suppresses both while it runs and queues `onWalletChanged`
 * at its end, still under the lock. The listeners only increment, since they run under the lock.
 */
class WalletChangeCounter {
    private val changes = AtomicLong()
    private val changeListener = WalletChangeEventListener { changes.incrementAndGet() }
    private val confidenceListener = TransactionConfidenceEventListener { _, _ -> changes.incrementAndGet() }
    private var attached: Wallet? = null

    /** The current count, after attaching to [wallet] (counted as a change) if it isn't the attached one. */
    @Synchronized
    fun count(wallet: Wallet?): Long {
        if (wallet !== attached) {
            attached?.let { previous ->
                previous.removeChangeEventListener(changeListener)
                previous.removeTransactionConfidenceEventListener(confidenceListener)
            }
            wallet?.let {
                it.addChangeEventListener(Threading.SAME_THREAD, changeListener)
                it.addTransactionConfidenceEventListener(Threading.SAME_THREAD, confidenceListener)
            }
            attached = wallet
            changes.incrementAndGet()
        }

        return changes.get()
    }
}
