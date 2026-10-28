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

    /** The sample interval: the span a normal interval is expected to have. */
    private val minute = 60_000L

    /** A realistic stat line: fields 14/15 utime/stime, 22 starttime. */
    private fun statLine(tid: Int, comm: String, state: Char, utime: Long, stime: Long, start: Long = 5000): String =
        "$tid ($comm) $state 1000 1000 0 0 -1 4210752 1200 0 0 0 $utime $stime 0 0 20 0 42 0 $start " +
            "1234567890 30000 18446744073709551615 0 0 0 0 0 4612 1073775864 0 0 0 -1 3 0 0 0 0 0\n"

    private fun stat(tid: Int, name: String, ticks: Long, start: Long = 5000, state: Char = 'S') =
        ThreadStat(tid, name, state, ticks, start)

    private fun snap(wallMs: Long, processTicks: Long, vararg threads: ThreadStat, scanMs: Long = 0) =
        Snapshot(wallMs, wallMs, processTicks, threads.associateBy { it.tid }, scanMs)

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
        assertEquals(Level.WARN, ThreadCpuStats.levelFor(r, clkTck, 60_000, null, 600_000, minute))
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
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(idle, clkTck, 60_000, null, tenMin, minute))
        assertEquals(Level.DEBUG, ThreadCpuStats.levelFor(idle, clkTck, 300_000, 0, tenMin, minute))
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(idle, clkTck, 600_000, 0, tenMin, minute))
        val active = ThreadCpuStats.diff(prev, snap(60_000, 600, stat(1, "a", 600))) // 10%
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(active, clkTck, 120_000, 60_000, tenMin, minute))
    }

    // ── formatting / name matching ────────────────────────────────────

    @Test
    fun `format line carries percent, top threads and churn`() {
        val prev = snap(0, 0, stat(1, "main", 0), stat(2, "gone", 0))
        val curr = snap(60_000, 9000, stat(1, "main", 100), stat(3, "tokio-runtime-w", 8000, state = 'R'), scanMs = 12)
        val line = ThreadCpuStats.formatLine(ThreadCpuStats.diff(prev, curr), clkTck)
        assertEquals(
            "proc=150.0% cpu=90000ms wall=60.0s up=60.0s scan=12ms thr=2 " +
                "top=[3 tokio-runtime-w R 80000ms, 1 main S 1000ms] " +
                "churn=+1[3 tokio-runtime-w] -1[2 gone] unattributed=9000ms",
            line
        )
    }

    @Test
    fun `a scan slower than the threshold is flagged SLOW_SCAN`() {
        val prev = snap(0, 0, stat(1, "main", 0), scanMs = ThreadCpuStats.SLOW_SCAN_MS) // a fast-enough baseline
        fun line(scanMs: Long) =
            ThreadCpuStats.formatLine(ThreadCpuStats.diff(prev, snap(60_000, 100, stat(1, "main", 100), scanMs = scanMs)), clkTck)
        assertEquals("proc=1.7% cpu=1000ms wall=60.0s up=60.0s scan=5000ms thr=1 top=[1 main S 1000ms]", line(5_000))
        assertEquals(
            "proc=1.7% cpu=1000ms wall=60.0s up=60.0s scan=5001ms thr=1 top=[1 main S 1000ms] SLOW_SCAN",
            line(5_001)
        )
        assertFalse(ThreadCpuStats.isSlowScan(ThreadCpuStats.SLOW_SCAN_MS))
        assertTrue(ThreadCpuStats.isSlowScan(ThreadCpuStats.SLOW_SCAN_MS + 1))
    }

    @Test
    fun `an interval whose baseline scan was slow is flagged SLOW_BASELINE and logs at INFO`() {
        // The baseline read the process total at t=0 but the threads ~60 s later.
        val prev = snap(0, 0, stat(1, "w1", 6_000), stat(2, "w2", 6_000), scanMs = 60_000)
        val curr = snap(120_000, 12_000, stat(1, "w1", 6_000), stat(2, "w2", 6_000), scanMs = 10)
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(60_000L, r.prevScanMs)
        assertEquals(
            "proc=100.0% cpu=120000ms wall=120.0s up=120.0s scan=10ms thr=2 top=[] " +
                "unattributed=120000ms SLOW_BASELINE(60000ms)",
            ThreadCpuStats.formatLine(r, clkTck)
        )
        assertTrue(ThreadCpuStats.hasScanDiagnostics(r))

        // Idle but with a slow baseline: INFO between heartbeats, not DEBUG.
        val idle = ThreadCpuStats.diff(
            snap(0, 0, stat(1, "a", 0), scanMs = ThreadCpuStats.SLOW_SCAN_MS + 1),
            snap(minute, 50, stat(1, "a", 50))
        )
        assertTrue(ThreadCpuStats.formatLine(idle, clkTck).endsWith(" SLOW_BASELINE(5001ms)"))
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(idle, clkTck, 300_000, 0, tenMin, minute))
    }

    @Test
    fun `a fast baseline scan adds no marker`() {
        val prev = snap(0, 0, stat(1, "a", 0), scanMs = ThreadCpuStats.SLOW_SCAN_MS)
        val r = ThreadCpuStats.diff(prev, snap(minute, 50, stat(1, "a", 50)))
        assertFalse(ThreadCpuStats.formatLine(r, clkTck).contains("SLOW_BASELINE"))
        assertFalse(ThreadCpuStats.hasScanDiagnostics(r))
        assertEquals(Level.DEBUG, ThreadCpuStats.levelFor(r, clkTck, 300_000, 0, tenMin, minute))
    }

    // ── blind spots (unreadable / rejected thread stats) ─────────────

    @Test
    fun `blind-spot fields are absent when zero`() {
        val prev = snap(0, 0, stat(1, "main", 0))
        val curr = Snapshot(60_000, 60_000, 100, mapOf(1 to stat(1, "main", 100)), 0, readErrors = 0, badStats = 0)
        val line = ThreadCpuStats.formatLine(ThreadCpuStats.diff(prev, curr), clkTck)
        assertEquals("proc=1.7% cpu=1000ms wall=60.0s up=60.0s scan=0ms thr=1 top=[1 main S 1000ms]", line)
    }

    @Test
    fun `blind-spot fields appear when nonzero, before SLOW_SCAN`() {
        val prev = snap(0, 0, stat(1, "main", 0))
        val curr = Snapshot(
            60_000, 60_000, 100, emptyMap(), 6_000,
            readErrors = 3, badStats = 2, listFailed = true
        )
        val report = ThreadCpuStats.diff(prev, curr)
        assertEquals(3, report.readErrors)
        assertEquals(2, report.badStats)
        assertTrue(report.listFailed)
        assertEquals(
            "proc=1.7% cpu=1000ms wall=60.0s up=60.0s scan=6000ms thr=0 top=[] " +
                "unattributed=1000ms readErr=3 badStat=2 TASK_LIST_FAILED SLOW_SCAN",
            ThreadCpuStats.formatLine(report, clkTck)
        )
    }

    // ── incomplete snapshots are not a churn baseline ─────────────────

    @Test
    fun `a thread missing from an incomplete previous scan is neither created nor ranked`() {
        val prev = Snapshot(0, 0, 100, mapOf(1 to stat(1, "main", 10)), 0, readErrors = 1)
        val curr = snap(60_000, 9_500, stat(1, "main", 20), stat(2, "missed", 9_000))
        val r = ThreadCpuStats.diff(prev, curr)
        assertTrue(r.created.isEmpty())
        assertTrue(r.exited.isEmpty())
        assertEquals(listOf(1), r.ranked.map { it.tid })
        assertEquals(1, r.noBaseline)
        // The process delta is exact; the missed thread's share lands in unattributed.
        assertEquals(9_390L, r.unattributedTicks)
        // Process is hot, but the no-baseline thread is never picked for a dump.
        assertEquals(listOf(1), ThreadCpuStats.hotThreads(r, clkTck).map { it.tid })
        assertEquals(
            "proc=156.7% cpu=94000ms wall=60.0s up=60.0s scan=0ms thr=2 top=[1 main S 100ms] " +
                "unattributed=93900ms noBaseline=1",
            ThreadCpuStats.formatLine(r, clkTck)
        )
    }

    @Test
    fun `every kind of blind spot in the previous scan withholds the baseline`() {
        val curr = snap(60_000, 100, stat(1, "main", 20), stat(2, "missed", 50))
        val prevs = listOf(
            Snapshot(0, 0, 0, mapOf(1 to stat(1, "main", 10)), 0, badStats = 1),
            Snapshot(0, 0, 0, emptyMap(), 0, listFailed = true)
        )
        for (prev in prevs) {
            val r = ThreadCpuStats.diff(prev, curr)
            assertTrue(r.created.isEmpty())
            assertFalse(r.ranked.any { it.tid == 2 })
        }
    }

    @Test
    fun `a reused tid is still exited plus created after an incomplete previous scan`() {
        val prev = Snapshot(0, 0, 100, mapOf(5 to stat(5, "old", 900, start = 100)), 0, readErrors = 1)
        val curr = snap(60_000, 1000, stat(5, "new", 30, start = 7000))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf("new"), r.created.map { it.name })
        assertEquals(listOf("old"), r.exited.map { it.name })
        assertEquals(0, r.noBaseline)
    }

    @Test
    fun `a thread missing from an incomplete current scan is not exited`() {
        val prev = snap(0, 100, stat(1, "main", 10), stat(2, "unseen", 50))
        val curr = Snapshot(60_000, 60_000, 400, mapOf(1 to stat(1, "main", 20)), 0, readErrors = 1)
        val r = ThreadCpuStats.diff(prev, curr)
        assertTrue(r.exited.isEmpty())
        assertTrue(r.created.isEmpty())
        assertEquals(0, r.noBaseline)
        assertEquals(listOf(1), r.ranked.map { it.tid })
    }

    @Test
    fun `complete scans keep created and exited churn`() {
        val prev = snap(0, 100, stat(1, "main", 10), stat(2, "gone", 50))
        val curr = snap(60_000, 400, stat(1, "main", 20), stat(3, "new", 40))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(listOf(3), r.created.map { it.tid })
        assertEquals(listOf(2), r.exited.map { it.tid })
        assertEquals(0, r.noBaseline)
        assertEquals(listOf(3, 1), r.ranked.map { it.tid })
    }

    // ── scan diagnostics lift the level ───────────────────────────────

    private val tenMin = 600_000L

    /** ~0.8% of a core: idle; the heartbeat went out at t=0, so normally DEBUG. */
    private fun idleReport(readErrors: Int = 0, badStats: Int = 0, listFailed: Boolean = false, scanMs: Long = 0) =
        ThreadCpuStats.diff(
            snap(0, 0, stat(1, "a", 0)),
            Snapshot(60_000, 60_000, 50, mapOf(1 to stat(1, "a", 50)), scanMs, readErrors, badStats, listFailed)
        )

    @Test
    fun `a clean idle sample is unchanged, one with any scan diagnostic logs at INFO`() {
        assertFalse(ThreadCpuStats.hasScanDiagnostics(idleReport()))
        assertEquals(Level.DEBUG, ThreadCpuStats.levelFor(idleReport(), clkTck, 300_000, 0, tenMin, minute))
        val degraded = listOf(
            idleReport(readErrors = 1),
            idleReport(badStats = 1),
            idleReport(listFailed = true),
            idleReport(scanMs = ThreadCpuStats.SLOW_SCAN_MS + 1)
        )
        for (r in degraded) {
            assertTrue(ThreadCpuStats.hasScanDiagnostics(r))
            assertEquals(Level.INFO, ThreadCpuStats.levelFor(r, clkTck, 300_000, 0, tenMin, minute))
        }
    }

    @Test
    fun `an idle sample with threads missing a baseline logs at INFO`() {
        val prev = Snapshot(0, 0, 0, mapOf(1 to stat(1, "a", 0)), 0, readErrors = 1)
        val curr = snap(60_000, 50, stat(1, "a", 40), stat(2, "missed", 10))
        val r = ThreadCpuStats.diff(prev, curr)
        assertEquals(1, r.noBaseline)
        assertTrue(ThreadCpuStats.isIdle(r.processDeltaTicks, clkTck, r.wallMs))
        assertTrue(ThreadCpuStats.hasScanDiagnostics(r))
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(r, clkTck, 300_000, 0, tenMin, minute))
    }

    @Test
    fun `a hot sample with scan diagnostics stays WARN`() {
        val prev = snap(0, 0, stat(1, "a", 0))
        val curr = Snapshot(
            60_000, 60_000, 12_000, mapOf(1 to stat(1, "a", 12_000)), 6_000,
            readErrors = 2, listFailed = true
        )
        val r = ThreadCpuStats.diff(prev, curr)
        assertTrue(ThreadCpuStats.hasScanDiagnostics(r))
        assertEquals(Level.WARN, ThreadCpuStats.levelFor(r, clkTck, 300_000, 0, tenMin, minute))
    }

    // ── GAP (frozen/asleep) lifts the level ───────────────────────────

    @Test
    fun `gap is a wall span over GAP_FACTOR expected intervals`() {
        assertFalse(ThreadCpuStats.isGap(ThreadCpuStats.GAP_FACTOR * minute, minute))
        assertTrue(ThreadCpuStats.isGap(ThreadCpuStats.GAP_FACTOR * minute + 1, minute))
        // After failed scans the span is expected to cover several intervals.
        assertFalse(ThreadCpuStats.isGap(3 * minute + 500, 2 * minute))
        assertTrue(ThreadCpuStats.isGap(5 * minute, 2 * minute))
        assertFalse(ThreadCpuStats.isGap(Long.MAX_VALUE, 0))
    }

    @Test
    fun `expected span includes the baseline scan time and each failed scan`() {
        // Timed scan-start to scan-start: a 60 s baseline scan + a 60 s sleep.
        val afterSlowBaseline = ThreadCpuStats.diff(
            snap(0, 0, stat(1, "a", 0), scanMs = minute),
            snap(2 * minute + 500, 50, stat(1, "a", 50))
        )
        val expected = ThreadCpuStats.expectedWallMs(afterSlowBaseline, minute, 0)
        assertEquals(2 * minute, expected)
        assertFalse(ThreadCpuStats.isGap(afterSlowBaseline.wallMs, expected))
        // Without the scan time the same span would read as a freeze.
        assertTrue(ThreadCpuStats.isGap(afterSlowBaseline.wallMs, minute))
        // No GAP lift (SLOW_BASELINE, which lifts it anyway, removed to isolate GAP).
        assertEquals(
            Level.DEBUG,
            ThreadCpuStats.levelFor(
                afterSlowBaseline.copy(prevScanMs = 0),
                clkTck,
                300_000,
                0,
                tenMin,
                expected
            )
        )
        // Two failed scans in between add two sleeps.
        assertEquals(3 * minute + 200, ThreadCpuStats.expectedWallMs(afterSlowBaseline.copy(prevScanMs = 200), minute, 2))
    }

    @Test
    fun `a genuine freeze is still a GAP after a slow baseline`() {
        val frozen = ThreadCpuStats.diff(
            snap(0, 0, stat(1, "a", 0), scanMs = minute),
            snap(10 * minute, 50, stat(1, "a", 50))
        )
        val expected = ThreadCpuStats.expectedWallMs(frozen, minute, 0)
        assertTrue(ThreadCpuStats.isGap(frozen.wallMs, expected))
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(frozen, clkTck, 300_000, 0, tenMin, expected))
        // A fast baseline keeps the plain one-interval expectation.
        val fast = ThreadCpuStats.diff(snap(0, 0, stat(1, "a", 0), scanMs = 12), snap(minute, 50, stat(1, "a", 50)))
        assertEquals(minute + 12, ThreadCpuStats.expectedWallMs(fast, minute, 0))
    }

    @Test
    fun `an idle GAP sample logs INFO between heartbeats, a hot one stays WARN, clean idle stays DEBUG`() {
        val prev = snap(0, 0, stat(1, "a", 0))
        // 10 minutes of wall, ~0.1% of a core: idle, but frozen/asleep for most of it.
        val idleGap = ThreadCpuStats.diff(prev, snap(10 * minute, 50, stat(1, "a", 50)))
        assertTrue(ThreadCpuStats.isGap(idleGap.wallMs, minute))
        assertFalse(ThreadCpuStats.hasScanDiagnostics(idleGap))
        assertEquals(Level.INFO, ThreadCpuStats.levelFor(idleGap, clkTck, 300_000, 0, tenMin, minute))

        val hotGap = ThreadCpuStats.diff(prev, snap(10 * minute, 60_000, stat(1, "a", 60_000))) // 100%
        assertEquals(Level.WARN, ThreadCpuStats.levelFor(hotGap, clkTck, 300_000, 0, tenMin, minute))

        val cleanIdle = ThreadCpuStats.diff(prev, snap(minute, 50, stat(1, "a", 50)))
        assertEquals(Level.DEBUG, ThreadCpuStats.levelFor(cleanIdle, clkTck, 300_000, 0, tenMin, minute))
        // The same 10-minute span is no GAP when it was expected to cover that many intervals.
        assertEquals(Level.DEBUG, ThreadCpuStats.levelFor(idleGap, clkTck, 300_000, 0, tenMin, 10 * minute))
    }

    // ── failure rate limiting ──────────────────────────────────────────

    @Test
    fun `failure throttle logs the first failure, then once per repeat interval`() {
        val throttle = ThreadCpuStats.FailureThrottle(tenMin)
        assertTrue(throttle.onFailure(0))
        assertFalse(throttle.onFailure(minute))
        assertFalse(throttle.onFailure(tenMin - 1))
        assertTrue(throttle.onFailure(tenMin))
        assertFalse(throttle.onFailure(tenMin + minute))
        assertEquals(5, throttle.failures)
    }

    @Test
    fun `failure throttle success reports the run length and re-arms`() {
        val throttle = ThreadCpuStats.FailureThrottle(tenMin)
        assertEquals(0, throttle.onSuccess())
        throttle.onFailure(0)
        throttle.onFailure(minute)
        assertEquals(2, throttle.onSuccess())
        assertEquals(0, throttle.failures)
        assertEquals(0, throttle.onSuccess())
        // A new failure right after recovery is logged again.
        assertTrue(throttle.onFailure(2 * minute))
    }

    @Test
    fun `tally counts a rejected stat line and keeps a good one`() {
        val tally = ThreadCpuStats.ScanTally()
        assertNull(tally.parse("12 (x) S 1 2 3"))
        assertEquals(4321, tally.parse(statLine(4321, "main", 'S', 1, 2))?.tid)
        assertEquals(1, tally.badStats)
        assertEquals(0, tally.readErrors)
    }

    @Test
    fun `tally ignores exited threads but counts other read failures, keeping the first`() {
        val tally = ThreadCpuStats.ScanTally()
        tally.readFailed(java.io.FileNotFoundException("/proc/self/task/9/stat: open failed: ENOENT (No such file or directory)"))
        tally.readFailed(java.io.IOException("read failed: ESRCH (No such process)"))
        tally.readFailed(java.io.FileNotFoundException("/proc/self/task/9/stat (No such file or directory)"))
        assertEquals(0, tally.readErrors)
        assertNull(tally.firstError)

        val denied = java.io.FileNotFoundException("/proc/self/task/7/stat: open failed: EACCES (Permission denied)")
        tally.readFailed(denied)
        tally.readFailed(java.io.IOException("EIO"))
        assertEquals(2, tally.readErrors)
        assertEquals(denied, tally.firstError)
        assertFalse(tally.listFailed)
        tally.listFailed(null)
        assertTrue(tally.listFailed)
    }

    @Test
    fun `java names match truncated comm`() {
        assertTrue(ThreadCpuStats.javaNameMatchesComm("DefaultDispatcher-worker-3", "DefaultDispatch"))
        assertTrue(ThreadCpuStats.javaNameMatchesComm("main", "main"))
        assertFalse(ThreadCpuStats.javaNameMatchesComm("mainline", "main"))
    }

    @Test
    fun `java names follow ART's truncation rule`() {
        // Over 15 bytes with a '.' and no '@': ART keeps the LAST 15 bytes.
        val okHttp = "OkHttp https://api.example.org/..."
        assertEquals("example.org/...", ThreadCpuStats.commForJavaName(okHttp))
        assertTrue(ThreadCpuStats.javaNameMatchesComm(okHttp, okHttp.takeLast(15)))
        assertFalse(ThreadCpuStats.javaNameMatchesComm(okHttp, okHttp.take(15)))
        // No '.': the FIRST 15, and a suffix does not match.
        val worker = "DefaultDispatcher-worker-3"
        assertTrue(ThreadCpuStats.javaNameMatchesComm(worker, "DefaultDispatch"))
        assertFalse(ThreadCpuStats.javaNameMatchesComm(worker, worker.takeLast(15)))
        // A '.' AND an '@': the FIRST 15.
        val at = "Thread@1a2b3c.pool-worker-7"
        assertTrue(ThreadCpuStats.javaNameMatchesComm(at, at.take(15)))
        assertFalse(ThreadCpuStats.javaNameMatchesComm(at, at.takeLast(15)))
        // Exactly 15 bytes or shorter: the whole name, even with a dot.
        assertTrue(ThreadCpuStats.javaNameMatchesComm("a.b.c.d.e.f.g.h", "a.b.c.d.e.f.g.h"))
        assertTrue(ThreadCpuStats.javaNameMatchesComm("io.x", "io.x"))
        assertFalse(ThreadCpuStats.javaNameMatchesComm("io.x", "io.y"))
        // Unrelated names never match.
        assertFalse(ThreadCpuStats.javaNameMatchesComm("Signal Catcher", "HeapTaskDaemon"))
        assertFalse(ThreadCpuStats.javaNameMatchesComm(okHttp, "OkHttp"))
    }
}
