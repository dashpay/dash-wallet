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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.Consumer

/** The safe-mode retry's waiter list (a destroyed recovery screen must not be retained). */
class SafeModeRetryWaitersTest {

    private val calls = ArrayList<Pair<String, Boolean>>()

    private fun waiter(name: String) = Consumer<Boolean> { calls.add(name to it) }

    @Test
    fun complete_callsEveryWaiterInOrder_andForgetsThem() {
        val waiters = SafeModeRetryWaiters()
        waiters.add(waiter("a"))
        waiters.add(waiter("b"))

        waiters.complete(true)

        assertEquals(listOf("a" to true, "b" to true), calls)
        assertTrue(waiters.isEmpty)
        waiters.complete(false) // nobody left to tell
        assertEquals(2, calls.size)
    }

    @Test
    fun removedWaiter_isNotCalled_theOthersAre() {
        val waiters = SafeModeRetryWaiters()
        val destroyed = waiter("destroyed")
        waiters.add(destroyed)
        waiters.add(waiter("recreated"))

        assertTrue(waiters.remove(destroyed))
        waiters.complete(false)

        assertEquals(listOf("recreated" to false), calls)
        assertTrue(waiters.isEmpty)
    }

    @Test
    fun remove_isByIdentity_andReportsWhetherItWasWaiting() {
        val waiters = SafeModeRetryWaiters()
        val a = waiter("a")
        waiters.add(a)

        assertFalse(waiters.remove(waiter("a"))) // an equal-looking but different waiter
        assertTrue(waiters.remove(a))
        assertFalse(waiters.remove(a))
        assertTrue(waiters.isEmpty)
    }

    @Test
    fun waiterRemovedDuringDelivery_isNotCalled() {
        val waiters = SafeModeRetryWaiters()
        val later = waiter("later")
        waiters.add(
            Consumer {
                calls.add("first" to it)
                waiters.remove(later)
            }
        )
        waiters.add(later)

        waiters.complete(true)

        assertEquals(listOf("first" to true), calls)
        assertTrue(waiters.isEmpty)
    }

    @Test
    fun waiterAddedDuringDelivery_waitsForTheNextCompletion() {
        val waiters = SafeModeRetryWaiters()
        waiters.add(
            Consumer {
                calls.add("first" to it)
                waiters.add(waiter("next"))
            }
        )

        waiters.complete(false)
        assertEquals(listOf("first" to false), calls)
        assertFalse(waiters.isEmpty)

        waiters.complete(true)
        assertEquals(listOf("first" to false, "next" to true), calls)
        assertTrue(waiters.isEmpty)
    }
}
