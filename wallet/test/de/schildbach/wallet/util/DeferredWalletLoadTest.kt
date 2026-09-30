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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The background-start wallet-load gate (12000022 start-up kills). */
class DeferredWalletLoadTest {

    /** A main-thread queue drained by hand, and a thread that plays the main thread. */
    private class Harness {
        val mainQueue = ConcurrentLinkedQueue<Runnable>()
        val mainExecutor = Executor { mainQueue.add(it) }
        @Volatile var mainThread: Thread = Thread.currentThread()
        fun drainMain() { while (true) (mainQueue.poll() ?: return).run() }
    }

    private fun gate(h: Harness, timeoutMs: Long = DeferredWalletLoad.OFF_MAIN_TIMEOUT_MS) =
        DeferredWalletLoad({ Thread.currentThread() === h.mainThread }, timeoutMs)

    @Test
    fun inactive_isANoOp() {
        val h = Harness()
        val g = gate(h)
        assertFalse(g.isInProgress())
        g.awaitForCaller() // returns immediately
    }

    @Test
    fun theWorkerPostsTheCompletion_whichRunsOnceOnMain() {
        val h = Harness()
        val g = gate(h)
        val completions = AtomicInteger()
        val workerDone = CountDownLatch(1)
        g.start(
            load = Runnable {},
            completeOnMain = Runnable { completions.incrementAndGet() },
            mainThread = h.mainExecutor,
            worker = Executor { Thread { it.run(); workerDone.countDown() }.start() }
        )
        assertTrue(workerDone.await(5, TimeUnit.SECONDS))
        assertTrue("posted, not yet run", g.isInProgress())
        h.drainMain()
        assertEquals(1, completions.get())
        assertFalse(g.isInProgress())
    }

    /**
     * A MAIN-thread caller (an activity opened mid-load, a widget update) waits
     * only for the parse and check, then runs the completion inline: it sees
     * a finished initialisation, and the posted copy later is a no-op.
     */
    @Test
    fun aMainThreadCaller_waitsForTheLoad_thenRunsTheCompletionInline() {
        val h = Harness()
        val g = gate(h)
        val loadGate = CountDownLatch(1)
        val completions = AtomicInteger()
        g.start(
            load = Runnable { loadGate.await() },
            completeOnMain = Runnable { completions.incrementAndGet() },
            mainThread = h.mainExecutor,
            worker = Executor { Thread(it).start() }
        )
        Thread { Thread.sleep(100); loadGate.countDown() }.start()

        g.awaitForCaller() // on the "main" thread

        assertEquals("completed inline before returning", 1, completions.get())
        assertFalse(g.isInProgress())
        h.drainMain()
        assertEquals("the posted completion is a no-op", 1, completions.get())
    }

    /** An OFF-main caller (the service's coroutine) waits for the whole completion. */
    @Test
    fun anOffMainCaller_waitsForTheMainThreadCompletion() {
        val h = Harness()
        val g = gate(h)
        val completed = AtomicInteger()
        g.start(
            load = Runnable {},
            completeOnMain = Runnable { completed.incrementAndGet() },
            mainThread = h.mainExecutor,
            worker = Executor { Thread(it).start() }
        )
        val sawCompletion = AtomicInteger(-1)
        val caller = Thread { g.awaitForCaller(); sawCompletion.set(completed.get()) }.apply { start() }
        Thread.sleep(200)
        assertTrue("still waiting: main has not run the completion", caller.isAlive)

        while (h.mainQueue.isEmpty()) Thread.sleep(5)
        h.drainMain()
        caller.join(5_000)
        assertEquals(1, sawCompletion.get())
    }

    /** A gated call made BY the load itself must not wait on the load. */
    @Test
    fun theWorkerThread_isExempt() {
        val h = Harness()
        val g = gate(h, timeoutMs = 10_000)
        val workerReturned = CountDownLatch(1)
        g.start(
            load = Runnable { g.awaitForCaller() },
            completeOnMain = Runnable {},
            mainThread = h.mainExecutor,
            worker = Executor { Thread { it.run(); workerReturned.countDown() }.start() }
        )
        assertTrue("the load did not wait on itself", workerReturned.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun aThrowingLoad_stillReleasesEveryWaiter() {
        val h = Harness()
        val g = gate(h)
        val completions = AtomicInteger()
        g.start(
            load = Runnable { throw OutOfMemoryError("simulated") },
            completeOnMain = Runnable { completions.incrementAndGet() },
            mainThread = h.mainExecutor,
            worker = Executor { Thread(it).start() }
        )
        g.awaitForCaller() // main caller: must not hang
        assertEquals(1, completions.get())
    }

    /** A caller that replaces or destroys the wallet never proceeds before the completion. */
    @Test
    fun anOffMainMutatingCaller_waitsPastTheTimeout_untilTheCompletionRuns() {
        val h = Harness()
        val g = gate(h, timeoutMs = 100)
        val completed = AtomicInteger()
        g.start(Runnable {}, Runnable { completed.incrementAndGet() }, h.mainExecutor, Executor { Thread(it).start() })
        val sawCompletion = AtomicInteger(-1)
        val caller = Thread { g.awaitForCaller(untilComplete = true); sawCompletion.set(completed.get()) }.apply { start() }
        Thread.sleep(400) // well past the reader timeout
        assertTrue("still waiting past the timeout", caller.isAlive)
        caller.interrupt()
        Thread.sleep(100)
        assertTrue("an interrupt does not release it either", caller.isAlive)

        while (h.mainQueue.isEmpty()) Thread.sleep(5)
        h.drainMain()
        caller.join(5_000)
        assertEquals(1, sawCompletion.get())
    }

    @Test
    fun anOffMainCaller_proceedsAfterTheTimeout_ifTheCompletionNeverRuns() {
        val h = Harness()
        val g = gate(h, timeoutMs = 200)
        g.start(Runnable {}, Runnable {}, h.mainExecutor, Executor { Thread(it).start() })
        val returned = CountDownLatch(1)
        Thread { g.awaitForCaller(); returned.countDown() }.start() // main never drains
        assertTrue(returned.await(3, TimeUnit.SECONDS))
    }

    // ── What counts as a background start ──

    @Test
    fun startReasons_onlyLauncherRecentsAndActivityAreForeground() {
        for (reason in listOf(6, 7, 11)) {
            assertTrue("reason $reason", DeferredWalletLoad.isForegroundStartReason(reason))
        }
        // alarm, backup, boot, broadcast, content provider, job, other, push, service
        for (reason in listOf(0, 1, 2, 3, 4, 5, 8, 9, 10)) {
            assertFalse("reason $reason", DeferredWalletLoad.isForegroundStartReason(reason))
        }
    }

    @Test
    fun importanceFallback_onlyForegroundAndVisibleAreForeground() {
        assertFalse("foreground", DeferredWalletLoad.isBackgroundImportance(100))
        assertFalse("visible", DeferredWalletLoad.isBackgroundImportance(200))
        assertFalse("unreadable", DeferredWalletLoad.isBackgroundImportance(0))
        assertTrue("foreground service: an alarm's startForegroundService", DeferredWalletLoad.isBackgroundImportance(125))
        assertTrue("perceptible", DeferredWalletLoad.isBackgroundImportance(230))
        assertTrue("service", DeferredWalletLoad.isBackgroundImportance(300))
        assertTrue("cached", DeferredWalletLoad.isBackgroundImportance(400))
    }
}
