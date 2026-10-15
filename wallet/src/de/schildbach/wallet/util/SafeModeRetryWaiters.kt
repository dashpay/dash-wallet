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

package de.schildbach.wallet.util

import java.util.function.Consumer

/**
 * Who is waiting for a safe-mode retry's wallet load to report back (see
 * `WalletApplication.retryWalletLoadAfterSafeMode`). Main thread only.
 *
 * A waiter usually holds the recovery screen (OnboardingActivity), and the load
 * can run for minutes on a large wallet. A screen that is destroyed meanwhile (a
 * rotation recreates it) [remove]s its waiter, so the application does not keep
 * every destroyed instance reachable until the load ends. A removed waiter is
 * never called, even when it is removed while the result is being delivered.
 */
class SafeModeRetryWaiters {
    private val waiting = ArrayList<Consumer<Boolean>>()

    /** The waiters of the delivery in progress that have not been called yet. */
    private var delivering: MutableList<Consumer<Boolean>>? = null

    /** Nobody is waiting (or left to call in a delivery in progress). */
    val isEmpty: Boolean
        get() = waiting.isEmpty() && delivering.isNullOrEmpty()

    fun add(waiter: Consumer<Boolean>) {
        waiting.add(waiter)
    }

    /** Forgets [waiter] (by identity); returns whether it was still waiting. */
    fun remove(waiter: Consumer<Boolean>): Boolean {
        val removed = waiting.removeAll { it === waiter }
        val removedFromDelivery = delivering?.removeAll { it === waiter } == true
        return removed || removedFromDelivery
    }

    /**
     * Calls every waiter with [loaded], in the order they were added, and
     * forgets them all. A waiter added during the delivery belongs to the next
     * retry and is not called with this result.
     */
    fun complete(loaded: Boolean) {
        val batch = ArrayList(waiting)
        waiting.clear()
        delivering = batch
        try {
            while (batch.isNotEmpty()) {
                batch.removeAt(0).accept(loaded)
            }
        } finally {
            delivering = null
        }
    }
}
