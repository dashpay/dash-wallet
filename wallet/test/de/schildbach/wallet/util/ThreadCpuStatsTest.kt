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

import de.schildbach.wallet.util.ThreadCpuStats.Level
import de.schildbach.wallet.util.ThreadCpuStats.Snapshot
import de.schildbach.wallet.util.ThreadCpuStats.ThreadStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadCpuStatsTest {

    private val clkTck = 100L

    /** A realistic stat line: fields 14/15 utime/stime, 22 starttime. */
    private fun statLine(tid: Int, comm: String, state: Char, utime: Long, stime: Long, start: Long = 5000): String =
        "$tid ($comm) $state 1000 1000 0 0 -1 4210752 1200 0 0 0 $utime $stime 0 0 20 0 42 0 $start " +
            "1234567890 30000 18446744073709551615 0 0 0 0 0 4612 1073775864 0 0 0 -1 3 0 0 0 0 0\n"

    private fun stat(tid: Int, name: String, ticks: Long, start: Long = 5000, state: Char = 'S') =
        ThreadStat(tid, name, state, ticks, start)

    private fun snap(wallMs: Long, processTicks: Long, vararg threads: ThreadStat) =
        Snapshot(wallMs, wallMs, processTicks, threads.associateBy { it.tid })

    // ── parseStatLine ─────────────────────────────────────────────────

    @Test
    fun `parses a plain stat line`() {
        val s = ThreadCpuStats.parseStatLine(statLine(4321, "tokio-runtime-w", 'R', 700, 300))
        assertEquals(ThreadStat(4321, "tokio-runtime-w", 'R', 1000, 5000), s)
    }

    @Test
    fun `thread name with spaces and parentheses is parsed up to the last paren`() {
        val s = ThreadCpuStats.parseStatLine(statLine(77, "a) b (c) R 9", 'S', 12, 3))
        assertEquals("a) b (c) R 9", s?.name)
        assertEquals('S', s?.state)
        assertEquals(15L, s?.ticks)
        assertEquals(77, s?.tid)
    }

    @Test
    fun `malformed lines return null instead of throwing`() {
        assertNull(ThreadCpuStats.parseStatLine(""))
        assertNull(ThreadCpuStats.parseStatLine("12 (truncated"))
        assertNull(ThreadCpuStats.parseStatLine("12 (x) S 1 2 3"))
        assertNull(ThreadCpuStats.parseStatLine("abc (x) S" + " 0".repeat(30)))
    }

    // ── diff ──────────────────────────────────────────────────────────

    @Test
    fun `ranks threads by delta, busiest first, and drops idle ones`() {
        val prev = snap(0, 100, stat(1, "main", 10), stat(2, "busy", 20), stat(3, "warm", 30), stat(4, "idle", 40))
        val curr = snap(60_000, 900, stat(1, "main", 15), stat(2, "busy", 620), stat(3, "warm", 130), stat(4, "idle", 40))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf(2, 3, 1), r.ranked.map { it.tid })
        assertEquals(listOf(600L, 100L, 5L), r.ranked.map { it.deltaTicks })
        assertEquals(800L, r.processDeltaTicks)
        assertEquals(95L, r.unattributedTicks)
        assertTrue(r.created.isEmpty())
        assertTrue(r.exited.isEmpty())
    }

    @Test
    fun `ranking is capped at topN`() {
        val prev = snap(0, 0, *(1..10).map { stat(it, "t$it", 0) }.toTypedArray())
        val curr = snap(60_000, 1000, *(1..10).map { stat(it, "t$it", it.toLong()) }.toTypedArray())
        val r = ThreadCpuStats.diff(prev, curr, topN = 5)
        assertEquals(listOf(10, 9, 8, 7, 6), r.ranked.map { it.tid })
    }

    @Test
    fun `a vanished thread is reported as exited and its CPU is unattributed`() {
        val prev = snap(0, 100, stat(1, "main", 10), stat(2, "gone", 50))
        val curr = snap(60_000, 400, stat(1, "main", 20))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf(2), r.exited.map { it.tid })
        assertTrue(r.created.isEmpty())
        assertEquals(listOf(1), r.ranked.map { it.tid })
        assertEquals(290L, r.unattributedTicks)
    }

    @Test
    fun `a new thread counts all of its CPU in the interval`() {
        val prev = snap(0, 100, stat(1, "main", 10))
        val curr = snap(60_000, 400, stat(1, "main", 20), stat(9, "new worker", 250))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf(9), r.created.map { it.tid })
        assertEquals(9, r.ranked.first().tid)
        assertEquals(250L, r.ranked.first().deltaTicks)
    }

    @Test
    fun `a reused tid with a different start time is exited plus created`() {
        val prev = snap(0, 100, stat(5, "old", 900, start = 100))
        val curr = snap(60_000, 1000, stat(5, "new", 30, start = 7000))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf("new"), r.created.map { it.name })
        assertEquals(listOf("old"), r.exited.map { it.name })
        assertEquals(30L, r.ranked.single().deltaTicks)
    }

    // ── hot / idle decisions ──────────────────────────────────────────

    @Test
    fun `hot threshold is half of one core over the interval`() {
        // 60 s wall at 100 ticks/s: 3000 ticks = 50% exactly, not hot; 3001 is.
        assertFalse(ThreadCpuStats.isHot(3000, clkTck, 60_000))
        assertTrue(ThreadCpuStats.isHot(3100, clkTck, 60_000))
        // The QA kill: ~two cores busy.
        assertEquals(190.0, ThreadCpuStats.cpuPercent(11_400, clkTck, 60_000), 0.01)
    }

    @Test
    fun `hot sample dumps each thread over the threshold`() {
        val prev = snap(0, 0, stat(1, "a", 0), stat(2, "b", 0), stat(3, "c", 0))
        val curr = snap(60_000, 12_000, stat(1, "a", 6000), stat(2, "b", 5800), stat(3, "c", 200))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf(1, 2), ThreadCpuStats.hotThreads(r, clkTck).map { it.tid })
        assertEquals(Level.WARN, ThreadCpuStats.levelFor(r, clkTck, 60_000, null, 600_000))
    }

    @Test
    fun `hot process with no single hot thread dumps the busiest`() {
        val prev = snap(0, 0, stat(1, "a", 0), stat(2, "b", 0), stat(3, "c", 0))
        val curr = snap(60_000, 6000, stat(1, "a", 2000), stat(2, "b", 2100), stat(3, "c", 1900))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf(2), ThreadCpuStats.hotThreads(r, clkTck).map { it.tid })
    }

    @Test
    fun `cool process has no hot threads`() {
        val prev = snap(0, 0, stat(1, "a", 0))
        val curr = snap(60_000, 600, stat(1, "a", 600))
        assertTrue(ThreadCpuStats.hotThreads(ThreadCpuStats.diff(prev, curr), clkTck).isEmpty())
    }

    @Test
    fun `idle samples log INFO at most every interval, DEBUG otherwise`() {
        val prev = snap(0, 0, stat(1, "a", 0))
        val idle = ThreadCpuStats.diff(prev, snap(60_000, 50, stat(1, "a", 50))) // ~0.8%
        val tenMin = 600_000L
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(idle, clkTck, 60_000, null, tenMin))
        assertEquals(Level.DEBUG, ThreadCpuStats.levelFor(idle, clkTck, 300_000, 0, tenMin))
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(idle, clkTck, 600_000, 0, tenMin))
        val active = ThreadCpuStats.diff(prev, snap(60_000, 600, stat(1, "a", 600))) // 10%
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(active, clkTck, 120_000, 60_000, tenMin))
    }

    // ── formatting / name matching ────────────────────────────────────

    @Test
    fun `format line carries percent, top threads and churn`() {
        val prev = snap(0, 0, stat(1, "main", 0), stat(2, "gone", 0))
        val curr = snap(60_000, 9000, stat(1, "main", 100), stat(3, "tokio-runtime-w", 8000, state = 'R'))
        val line = ThreadCpuStats.formatLine(ThreadCpuStats.diff(prev, curr), clkTck)
        assertEquals(
            "proc=150.0% cpu=90000ms wall=60.0s up=60.0s thr=2 " +
                "top=[3 tokio-runtime-w R 80000ms, 1 main S 1000ms] " +
                "churn=+1[3 tokio-runtime-w] -1[2 gone] unattributed=9000ms",
            line
        )
    }

    @Test
    fun `java names match truncated comm`() {
        assertTrue(ThreadCpuStats.javaNameMatchesComm("DefaultDispatcher-worker-3", "DefaultDispatch"))
        assertTrue(ThreadCpuStats.javaNameMatchesComm("main", "main"))
        assertFalse(ThreadCpuStats.javaNameMatchesComm("mainline", "main"))
    }
}
