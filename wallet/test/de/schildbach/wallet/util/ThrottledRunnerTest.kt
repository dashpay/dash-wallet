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

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The burst coalescer behind the peer-connectivity notification fix: 233 peer
 * connect/disconnect events in one session each used to post their own
 * main-thread NotificationManager binder call — the parked frame in all 7 of
 * the tester's ANR stacks. The contract here: at most one action per
 * interval, latest state wins, nothing is lost.
 */
class ThrottledRunnerTest {

    /** A deterministic single-threaded "handler": runnables run when [advanceTo] reaches their due time. */
    private class FakeScheduler {
        var nowMs = 0L
        private val tasks = mutableListOf<Pair<Long, Runnable>>()

        fun postDelayed(r: Runnable, delayMs: Long) {
            tasks.add(nowMs + delayMs to r)
        }

        fun advanceTo(timeMs: Long) {
            while (true) {
                val next = tasks.filter { it.first <= timeMs }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                nowMs = next.first
                next.second.run()
            }
            nowMs = timeMs
        }
    }

    private fun runner(intervalMs: Long, scheduler: FakeScheduler, action: Runnable) =
        ThrottledRunner(intervalMs, scheduler::postDelayed, { scheduler.nowMs }, action)

    // ── nextDelayMs: the pure rate-limit arithmetic ──────────────────────

    @Test
    fun nextDelay_table() {
        // long-idle (or never ran): run immediately
        assertEquals(0L, ThrottledRunner.nextDelayMs(lastRunAtMs = 0, nowMs = 5_000, intervalMs = 1_000))
        // ran just now: wait the full interval
        assertEquals(1_000L, ThrottledRunner.nextDelayMs(lastRunAtMs = 5_000, nowMs = 5_000, intervalMs = 1_000))
        // mid-interval: wait the remainder
        assertEquals(400L, ThrottledRunner.nextDelayMs(lastRunAtMs = 5_000, nowMs = 5_600, intervalMs = 1_000))
        // clock skew can never produce a delay beyond one interval
        assertEquals(1_000L, ThrottledRunner.nextDelayMs(lastRunAtMs = 9_000, nowMs = 5_000, intervalMs = 1_000))
    }

    // ── burst coalescing ─────────────────────────────────────────────────

    @Test
    fun aBurstOfEvents_coalescesToOneRunPerInterval() {
        val scheduler = FakeScheduler()
        val runs = AtomicInteger()
        val throttled = runner(1_000, scheduler) { runs.incrementAndGet() }

        // The tester's shape: hundreds of peer events in a burst.
        repeat(233) { throttled.schedule() }
        scheduler.advanceTo(999)
        assertEquals("nothing runs before the interval elapses mid-burst? first run is immediate at t=0", 1, runs.get())

        // A second burst within the same interval coalesces to ONE more run.
        repeat(50) { throttled.schedule() }
        scheduler.advanceTo(2_000)
        assertEquals(2, runs.get())
    }

    @Test
    fun firstEventAfterIdle_runsImmediately() {
        val scheduler = FakeScheduler()
        scheduler.advanceTo(60_000)
        val runs = AtomicInteger()
        val throttled = runner(1_000, scheduler) { runs.incrementAndGet() }

        throttled.schedule()
        scheduler.advanceTo(60_000) // zero-delay tasks run at once
        assertEquals("a lone event must not be delayed", 1, runs.get())
    }

    @Test
    fun eventArrivingDuringTheAction_isNeverLost() {
        val scheduler = FakeScheduler()
        val runs = AtomicInteger()
        var throttled: ThrottledRunner? = null
        throttled = runner(1_000, scheduler) {
            if (runs.incrementAndGet() == 1) {
                // an event lands WHILE the action is running
                throttled!!.schedule()
            }
        }
        throttled.schedule()
        scheduler.advanceTo(0)
        assertEquals(1, runs.get())
        scheduler.advanceTo(1_000)
        assertEquals("the mid-action event must produce a follow-up run", 2, runs.get())
    }

    @Test
    fun steadyEventStream_neverExceedsOneRunPerInterval() {
        val scheduler = FakeScheduler()
        val runs = AtomicInteger()
        val throttled = runner(1_000, scheduler) { runs.incrementAndGet() }

        // one event every 100ms for 10 simulated seconds
        for (t in 0..10_000L step 100) {
            scheduler.advanceTo(t)
            throttled.schedule()
        }
        scheduler.advanceTo(11_000)
        assertTrue("expected ~11 runs for 10s at 1/s, got ${runs.get()}", runs.get() in 10..12)
    }
}
