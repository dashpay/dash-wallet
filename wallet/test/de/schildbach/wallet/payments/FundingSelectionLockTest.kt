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

package de.schildbach.wallet.payments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wipe barrier: a wipe never proceeds while an admitted hold is running. It cancels the
 * holds under way and waits, with no timeout, for every one of them to release.
 */
class FundingSelectionLockTest {

    /** Far past any drain timeout the lock ever had (30 s). */
    private val longerThanAnyTimeout = 10 * 60_000L

    /** Released after each test, so a failed assertion never leaves a non-cancellable hold hanging. */
    private val toRelease = mutableListOf<CompletableDeferred<Unit>>()

    private fun gate() = CompletableDeferred<Unit>().also { toRelease += it }

    private fun guarded(body: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest(timeout = kotlin.time.Duration.parse("20s")) {
            try {
                body()
            } finally {
                toRelease.forEach { it.complete(Unit) }
                coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
            }
        }

    @Test
    fun `a wipe cancels an admitted hold and runs only once it has released`() = guarded {
        val lock = FundingSelectionLock()
        val holding = gate()
        var released = false
        val holder = async {
            runCatching {
                lock.withShared {
                    try {
                        holding.complete(Unit)
                        awaitCancellation() // a send waiting on the network
                    } finally {
                        released = true
                    }
                }
            }
        }
        holding.await()

        var releasedWhenTheWipeRan: Boolean? = null
        val wipe = async { lock.exclusiveForWipe { releasedWhenTheWipeRan = released } }
        advanceTimeBy(longerThanAnyTimeout)
        runCurrent()

        assertTrue("the wipe never ran", wipe.isCompleted)
        assertEquals("the wipe ran while a hold was still under way", true, releasedWhenTheWipeRan)
        val outcome = holder.await().exceptionOrNull()
        assertTrue("the hold was not cancelled by the wipe: $outcome", outcome is CancellationException)
    }

    @Test
    fun `a wipe waits, with no timeout, for a hold inside a call that ignores cancellation`() = guarded {
        // a native SDK broadcast cannot be interrupted: the wipe must wait for it to return
        val lock = FundingSelectionLock()
        val holding = gate()
        val nativeReturns = gate()
        val holder = async {
            lock.withShared {
                withContext(NonCancellable) {
                    holding.complete(Unit)
                    nativeReturns.await()
                }
                "broadcast"
            }
        }
        holding.await()
        var wipeRan = false
        val wipe = async { lock.exclusiveForWipe { wipeRan = true } }

        advanceTimeBy(longerThanAnyTimeout)
        runCurrent()
        assertFalse("the wipe went ahead while a hold was in a native call", wipeRan)

        nativeReturns.complete(Unit)
        runCurrent()
        wipe.await()
        assertTrue(wipeRan)
        // what the hold finished before it saw the cancellation is not lost
        assertEquals("broadcast", holder.await())
    }

    @Test
    fun `a hold that takes the lock again does not deadlock a wipe, nor count twice`() = guarded {
        val lock = FundingSelectionLock()
        val holding = gate()
        val wipeAsked = gate()
        var nestedDone = false
        val holder = async {
            runCatching {
                lock.withShared {
                    holding.complete(Unit)
                    // the wipe is waiting for this hold now; a nested hold (here, in a child
                    // coroutine, as a broadcast inside a send would take it) must not wait for it
                    withContext(NonCancellable) {
                        wipeAsked.await()
                        coroutineScope { async { lock.withShared { "nested" } }.await() }
                        nestedDone = true
                    }
                }
            }
        }
        holding.await()
        var nestedDoneWhenTheWipeRan: Boolean? = null
        val wipe = async { lock.exclusiveForWipe { nestedDoneWhenTheWipeRan = nestedDone } }
        runCurrent()
        wipeAsked.complete(Unit)
        advanceTimeBy(longerThanAnyTimeout)
        runCurrent()

        assertTrue("the wipe deadlocked on a nested hold", wipe.isCompleted)
        assertEquals("the wipe ran while the nested hold was stuck behind it", true, nestedDoneWhenTheWipeRan)
        assertTrue("the hold that finished its nested work failed: ${holder.await()}", holder.await().isSuccess)
        // released exactly once: the next wallet's wipe is not left waiting for a phantom hold
        val next = async { lock.exclusiveForWipe { "again" } }
        runCurrent()
        assertTrue("a nested hold was counted twice", next.isCompleted)
    }

    @Test
    fun `nothing a hold awaits needs the wipe to finish first`() = guarded {
        // a hold may wait for other work of its own payment (a reservation release, the record
        // write of a quarantine): none of that takes the lock exclusively, so the wipe, which
        // cancels the hold first, never waits on itself
        val lock = FundingSelectionLock()
        val holding = gate()
        val otherWork = gate()
        var released = false
        val holder = async {
            runCatching {
                lock.withShared {
                    try {
                        holding.complete(Unit)
                        otherWork.await()
                    } finally {
                        released = true
                    }
                }
            }
        }
        holding.await()
        var releasedWhenTheWipeRan: Boolean? = null
        val wipe = async { lock.exclusiveForWipe { releasedWhenTheWipeRan = released } }
        advanceTimeBy(longerThanAnyTimeout)
        runCurrent()
        assertTrue("the wipe waited on work its hold was cancelled out of", wipe.isCompleted)
        assertEquals("the wipe ran while a hold was still under way", true, releasedWhenTheWipeRan)
        assertTrue(holder.await().exceptionOrNull() is CancellationException)
    }

    @Test
    fun `a hold asked for while the wipe runs is refused, and the next wallet's is not`() = guarded {
        val lock = FundingSelectionLock()
        val inWipe = gate()
        val finishWipe = gate()
        val wipe = async { lock.exclusiveForWipe { inWipe.complete(Unit); finishWipe.await() } }
        inWipe.await()
        val late = async { runCatching { lock.withShared { "selected" } } }
        runCurrent()
        finishWipe.complete(Unit)
        wipe.await()
        assertTrue(late.await().exceptionOrNull() is WalletWipedException)
        assertEquals("selected", lock.withShared { "selected" })
    }
}
