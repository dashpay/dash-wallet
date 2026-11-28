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

import java.io.FileNotFoundException
import java.util.Locale

/**
 * The PURE half of [ThreadCpuSampler]: parsing `/proc/.../stat` lines,
 * diffing two snapshots into ranked per-thread CPU deltas, the hot/idle
 * decisions and the log-line formatting. Takes and returns plain values only
 * (no files, no Android types), so every rule is unit-testable on the JVM.
 */
object ThreadCpuStats {

    /** Process CPU over an interval, as % of ONE core, above which a sample is "hot" (WARN). */
    const val HOT_PERCENT_OF_ONE_CORE = 50.0

    /** Process CPU over an interval, as % of one core, below which the process counts as idle. */
    const val IDLE_PERCENT_OF_ONE_CORE = 2.0

    /** How many of the busiest threads go into the one-line summary. */
    const val TOP_N = 5

    /** Cap on created/exited tids printed per line (the counts are always exact). */
    const val MAX_CHURN_LISTED = 8

    /**
     * A snapshot read slower than this gets ` SLOW_SCAN`: the process total is
     * read first and the threads after, so over a slow scan the per-thread
     * numbers no longer line up with `proc`/`cpu`/`unattributed`.
     */
    const val SLOW_SCAN_MS = 5_000L

    /**
     * One line of `/proc/<pid>/stat` or `/proc/<pid>/task/<tid>/stat`.
     *
     * @param ticks utime + stime (fields 14 + 15), in clock ticks.
     * @param startTicks field 22, the start time since boot in clock ticks.
     *   A tid that exits and is reused by a NEW thread between two samples
     *   keeps its number but gets a different start time; [diff] uses this to
     *   treat it as exited + created rather than a (possibly negative) delta.
     */
    data class ThreadStat(
        val tid: Int,
        val name: String,
        val state: Char,
        val ticks: Long,
        val startTicks: Long
    )

    /** One thread's CPU over an interval. */
    data class ThreadDelta(
        val tid: Int,
        val name: String,
        val state: Char,
        val deltaTicks: Long
    )

    /**
     * Everything needed to report one interval.
     *
     * @param ranked live threads, busiest first, zero-delta threads excluded.
     * @param created threads present now but not in the previous snapshot.
     *   Only when that snapshot was complete (see [Snapshot.isComplete]):
     *   otherwise a thread absent from it may just have been missed, and is
     *   counted in [noBaseline] instead.
     * @param exited threads present before but gone now (their CPU in the
     *   interval is NOT in [ranked] — it shows up in [unattributedTicks]).
     *   Only when the CURRENT snapshot is complete: a thread missing from a
     *   partial scan may still be alive.
     * @param processDeltaTicks the process total (`/proc/self/stat`), which
     *   includes threads that exited during the interval.
     * @param unattributedTicks process delta minus the sum of [ranked]:
     *   CPU burnt by threads that died before we could see them. A large
     *   value means short-lived thread churn.
     * @param noBaseline live threads absent from an INCOMPLETE previous
     *   snapshot: their start-of-interval ticks are unknown, so they are in
     *   neither [ranked] nor [created], and their CPU is in [unattributedTicks].
     * @param scanMs how long the CURRENT snapshot took to read (see [Snapshot.scanMs]).
     * @param prevScanMs how long the PREVIOUS snapshot (this interval's
     *   baseline) took to read: a slow baseline skews the deltas just as a
     *   slow current scan does.
     * @param readErrors / [badStats] / [listFailed] the CURRENT snapshot's
     *   blind spots (see [Snapshot]): threads it could not see for a reason
     *   other than having exited, so [threadCount] and [unattributedTicks]
     *   are not to be trusted when they are nonzero.
     */
    data class IntervalReport(
        val wallMs: Long,
        val uptimeMs: Long,
        val scanMs: Long,
        val threadCount: Int,
        val processDeltaTicks: Long,
        val ranked: List<ThreadDelta>,
        val created: List<ThreadStat>,
        val exited: List<ThreadStat>,
        val unattributedTicks: Long,
        val readErrors: Int = 0,
        val badStats: Int = 0,
        val listFailed: Boolean = false,
        val noBaseline: Int = 0,
        val prevScanMs: Long = 0
    )

    /**
     * A point-in-time view of the process. Times are `SystemClock` millis.
     *
     * @param scanMs elapsedRealtime spent reading `/proc/self/stat` plus every
     *   `/proc/self/task/<tid>/stat` — the window over which this "point" is smeared.
     * @param readErrors per-thread stat reads that failed for a reason OTHER
     *   than the thread having exited (see [isThreadGone]) — e.g. EACCES.
     * @param badStats per-thread stat lines that were read but rejected by
     *   [parseStatLine].
     * @param listFailed `/proc/self/task` itself could not be listed, so
     *   [threads] is empty because we were blind, not because there were none.
     */
    data class Snapshot(
        val elapsedRealtimeMs: Long,
        val uptimeMs: Long,
        val processTicks: Long,
        val threads: Map<Int, ThreadStat>,
        val scanMs: Long,
        val readErrors: Int = 0,
        val badStats: Int = 0,
        val listFailed: Boolean = false
    ) {
        /** Whether [threads] holds every live thread (no blind spot in this scan). */
        val isComplete: Boolean
            get() = readErrors == 0 && badStats == 0 && !listFailed
    }

    /**
     * Per-scan bookkeeping of what a snapshot could NOT see, so a blind scan
     * doesn't pass for `thr=0` or for thread churn. Not thread-safe: one per scan.
     */
    class ScanTally {
        var readErrors = 0
            private set
        var badStats = 0
            private set
        var listFailed = false
            private set

        /** The first non-[isThreadGone] read failure — at most one gets logged per scan. */
        var firstError: Throwable? = null
            private set

        /** [parseStatLine], counting a rejected line in [badStats]. */
        fun parse(line: String): ThreadStat? = parseStatLine(line).also { if (it == null) badStats++ }

        /** A per-thread read failed; a thread that simply exited is not counted. */
        fun readFailed(t: Throwable) {
            if (isThreadGone(t)) return
            readErrors++
            if (firstError == null) firstError = t
        }

        /** Listing the task dir failed ([t] null when `list()` just returned null). */
        fun listFailed(t: Throwable?) {
            listFailed = true
            if (firstError == null && t != null) firstError = t
        }
    }

    /**
     * Rate limit for a failure that can recur every interval (an unreadable
     * `/proc/self/stat`, a report that throws): the first failure since the
     * last success is logged, then at most one per [repeatMs], so a persistent
     * failure stays visible without a line per interval. Not thread-safe.
     */
    class FailureThrottle(private val repeatMs: Long) {
        /** Failures since the last [onSuccess]. */
        var failures = 0
            private set
        private var lastLoggedMs: Long? = null

        /** Counts a failure at [nowMs]; true when this one should be logged. */
        fun onFailure(nowMs: Long): Boolean {
            failures++
            val last = lastLoggedMs
            if (last != null && nowMs - last < repeatMs) return false
            lastLoggedMs = nowMs
            return true
        }

        /** Ends a failure run; returns how many failures it ended (0 if none). */
        fun onSuccess(): Int {
            val ended = failures
            failures = 0
            lastLoggedMs = null
            return ended
        }
    }

    /**
     * Whether a /proc read failed because the thread exited between listing and
     * reading — normal churn, not a blind spot. Android reports that as
     * `open failed: ENOENT` (or ESRCH on a read racing the exit); the JVM as
     * `(No such file or directory)`.
     */
    fun isThreadGone(t: Throwable): Boolean {
        val msg = t.message ?: return t is FileNotFoundException
        return GONE_MARKERS.any { msg.contains(it) }
    }

    private val GONE_MARKERS = listOf("ENOENT", "ESRCH", "No such file", "No such process")

    /**
     * Parses one stat line. Field 2 (comm) is wrapped in parentheses and may
     * itself contain spaces and ')' — so the name runs from the FIRST '(' to
     * the LAST ')'. Returns null on anything malformed (never throws).
     */
    fun parseStatLine(line: String): ThreadStat? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open <= 0 || close < open) return null
        val tid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val name = line.substring(open + 1, close)
        // After ") ": rest[0] is field 3 (state), so field N is rest[N - 3].
        val rest = line.substring(close + 1).trim().split(' ')
        if (rest.size < 20) return null
        val state = rest[0].singleOrNull() ?: return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        val start = rest[19].toLongOrNull() ?: return null
        return ThreadStat(tid, name, state, utime + stime, start)
    }

    /**
     * Diffs two snapshots into one interval report. An incomplete snapshot is
     * never read as churn: a thread absent from an incomplete [prev] has no
     * baseline (counted in [IntervalReport.noBaseline], its CPU left
     * unattributed rather than its whole lifetime charged to this interval),
     * and a thread absent from an incomplete [curr] is not reported as exited.
     */
    fun diff(prev: Snapshot, curr: Snapshot, topN: Int = TOP_N): IntervalReport {
        val created = ArrayList<ThreadStat>()
        val exited = ArrayList<ThreadStat>()
        val deltas = ArrayList<ThreadDelta>(curr.threads.size)
        var attributed = 0L
        var noBaseline = 0
        for (now in curr.threads.values) {
            val before = prev.threads[now.tid]
            val delta = if (before == null && !prev.isComplete) {
                // Possibly just missed by the previous scan: its delta is unknown.
                noBaseline++
                continue
            } else if (before == null || before.startTicks != now.startTicks) {
                // New thread (or a reused tid): ALL of its CPU fell in this interval.
                created.add(now)
                if (before != null) exited.add(before)
                now.ticks
            } else {
                (now.ticks - before.ticks).coerceAtLeast(0)
            }
            attributed += delta
            if (delta > 0) deltas.add(ThreadDelta(now.tid, now.name, now.state, delta))
        }
        if (curr.isComplete) {
            for (before in prev.threads.values) {
                if (!curr.threads.containsKey(before.tid)) exited.add(before)
            }
        }
        deltas.sortWith(compareByDescending<ThreadDelta> { it.deltaTicks }.thenBy { it.tid })
        created.sortBy { it.tid }
        exited.sortBy { it.tid }
        val processDelta = (curr.processTicks - prev.processTicks).coerceAtLeast(0)
        return IntervalReport(
            wallMs = curr.elapsedRealtimeMs - prev.elapsedRealtimeMs,
            uptimeMs = curr.uptimeMs - prev.uptimeMs,
            scanMs = curr.scanMs,
            threadCount = curr.threads.size,
            processDeltaTicks = processDelta,
            ranked = if (deltas.size > topN) deltas.subList(0, topN).toList() else deltas,
            created = created,
            exited = exited,
            unattributedTicks = (processDelta - attributed).coerceAtLeast(0),
            readErrors = curr.readErrors,
            badStats = curr.badStats,
            listFailed = curr.listFailed,
            noBaseline = noBaseline,
            prevScanMs = prev.scanMs
        )
    }

    fun ticksToMs(ticks: Long, clkTck: Long): Long = if (clkTck <= 0) 0 else ticks * 1000 / clkTck

    /** CPU over [wallMs] as % of one core (two busy cores = 200%). */
    fun cpuPercent(ticks: Long, clkTck: Long, wallMs: Long): Double =
        if (wallMs <= 0 || clkTck <= 0) 0.0 else ticksToMs(ticks, clkTck) * 100.0 / wallMs

    fun isHot(ticks: Long, clkTck: Long, wallMs: Long, thresholdPct: Double = HOT_PERCENT_OF_ONE_CORE): Boolean =
        cpuPercent(ticks, clkTck, wallMs) > thresholdPct

    fun isIdle(ticks: Long, clkTck: Long, wallMs: Long): Boolean =
        cpuPercent(ticks, clkTck, wallMs) < IDLE_PERCENT_OF_ONE_CORE

    fun isSlowScan(scanMs: Long): Boolean = scanMs > SLOW_SCAN_MS

    /** A wall span above this many expected intervals is flagged as a freeze/sleep (`GAP`). */
    const val GAP_FACTOR = 2

    /**
     * Whether an interval's wall span shows the process was frozen or the
     * device asleep. [expectedIntervalMs] is what the span should have been
     * (see [expectedWallMs]).
     */
    fun isGap(wallMs: Long, expectedIntervalMs: Long): Boolean =
        expectedIntervalMs > 0 && wallMs > GAP_FACTOR * expectedIntervalMs

    /**
     * The wall span [report] should have, for [isGap]. A snapshot is timed at
     * its scan START, so the span runs from the start of the baseline scan to
     * the start of this one: the baseline's own scan time
     * ([IntervalReport.prevScanMs]) plus one [intervalMs] sleep, plus one more
     * per failed scan in between ([failedScans]; those kept the older baseline),
     * plus the wall time those failed scans took ([failedScanMs]), so a slow
     * failed read is not mistaken for a freeze.
     */
    fun expectedWallMs(report: IntervalReport, intervalMs: Long, failedScans: Int, failedScanMs: Long = 0): Long =
        report.prevScanMs.coerceAtLeast(0) + intervalMs * (1 + failedScans.coerceAtLeast(0)) +
            failedScanMs.coerceAtLeast(0)

    /**
     * The threads that get a detailed dump (wchan + Java stack) on a hot
     * sample: every thread itself over the threshold, or — if the process is
     * hot only because several threads share the load — the busiest one. When
     * the known threads together are not hot, the excess is unattributed (a
     * missed or exited thread) and no known thread is dumped in its place.
     */
    fun hotThreads(report: IntervalReport, clkTck: Long, thresholdPct: Double = HOT_PERCENT_OF_ONE_CORE): List<ThreadDelta> {
        if (!isHot(report.processDeltaTicks, clkTck, report.wallMs, thresholdPct)) return emptyList()
        val over = report.ranked.filter { isHot(it.deltaTicks, clkTck, report.wallMs, thresholdPct) }
        if (over.isNotEmpty()) return over
        val attributed = report.processDeltaTicks - report.unattributedTicks
        return if (isHot(attributed, clkTck, report.wallMs, thresholdPct)) report.ranked.take(1) else emptyList()
    }

    /**
     * Suffix for the session's startup line when its baseline scan had a blind
     * spot: the first interval's `noBaseline` covers only missed threads still
     * alive at the next scan, so one that exits in between would leave that
     * sample looking clean. Empty for a complete baseline.
     */
    fun baselineNote(baseline: Snapshot): String =
        if (baseline.isComplete) {
            ""
        } else {
            " INCOMPLETE_BASELINE" + blindSpots(baseline.readErrors, baseline.badStats, baseline.listFailed)
        }

    /** The kernel wait channel for display: `?` when unknown, `running` for `0` (on a CPU). */
    fun formatWchan(raw: String?): String = when (val w = raw?.trim()) {
        null, "" -> "?"
        "0" -> "running"
        else -> w
    }

    /**
     * Whether the CURRENT scan had a blind spot or was too slow to be
     * consistent (`readErr`, `badStat`, `TASK_LIST_FAILED`, `SLOW_SCAN`), or
     * the PREVIOUS scan, this interval's baseline, was too slow (`SLOW_BASELINE`)
     * or incomplete, leaving live threads without a baseline (`noBaseline`).
     */
    fun hasScanDiagnostics(report: IntervalReport): Boolean =
        report.readErrors > 0 || report.badStats > 0 || report.listFailed || isSlowScan(report.scanMs) ||
            isSlowScan(report.prevScanMs) || report.noBaseline > 0

    enum class Level { DEBUG, INFO, WARN }

    /**
     * Hot → WARN every time. Active (between idle and hot) → INFO. A sample
     * with [hasScanDiagnostics] or an [isGap] span → at least INFO, so neither
     * a degraded scan nor a freeze is filtered out by the INFO root logger.
     * Idle → INFO at most once per [idleInfoIntervalMs] (so a quiet log still
     * shows the sampler alive), DEBUG otherwise. Thread churn alone does not
     * lift an idle sample.
     */
    fun levelFor(
        report: IntervalReport,
        clkTck: Long,
        nowMs: Long,
        lastInfoMs: Long?,
        idleInfoIntervalMs: Long,
        expectedIntervalMs: Long
    ): Level = when {
        isHot(report.processDeltaTicks, clkTck, report.wallMs) -> Level.WARN
        !isIdle(report.processDeltaTicks, clkTck, report.wallMs) -> Level.INFO
        hasScanDiagnostics(report) -> Level.INFO
        isGap(report.wallMs, expectedIntervalMs) -> Level.INFO
        lastInfoMs == null || nowMs - lastInfoMs >= idleInfoIntervalMs -> Level.INFO
        else -> Level.DEBUG
    }

    /**
     * One compact line, e.g.
     * `proc=187.3% cpu=112400ms wall=60.0s up=60.0s scan=12ms thr=142 top=[12345 tokio-rt R 41230ms, ...] churn=+2[...] -1[...] unattributed=230ms`
     * (the caller adds the greppable prefix). `wall` is elapsedRealtime
     * (counts deep sleep and freezes), `up` is uptime (stops in deep sleep):
     * a wall gap far beyond the interval with `up` keeping pace means the
     * process was frozen while the device was awake. `scan` is how long the
     * current snapshot took to read; past [SLOW_SCAN_MS] the line ends in
     * ` SLOW_SCAN` and its per-thread numbers are not consistent with `proc`;
     * likewise ` SLOW_BASELINE(<n>ms)` when the PREVIOUS scan (the interval's
     * baseline) took that long, since it skews the deltas the same way.
     * Blind spots appear only when nonzero: `readErr=<n>` (thread stats that
     * could not be read, other than exited threads), `badStat=<n>` (stat
     * lines the parser rejected) and ` TASK_LIST_FAILED` (no thread was seen).
     * `noBaseline=<n>` (only when nonzero, after `unattributed`) counts live
     * threads the PREVIOUS, incomplete scan missed: their CPU is in
     * `unattributed`, not in `top` or `churn`.
     */
    fun formatLine(report: IntervalReport, clkTck: Long): String {
        val sb = StringBuilder(256)
        sb.append("proc=").append(fmt1(cpuPercent(report.processDeltaTicks, clkTck, report.wallMs))).append('%')
        sb.append(" cpu=").append(ticksToMs(report.processDeltaTicks, clkTck)).append("ms")
        sb.append(" wall=").append(fmt1(report.wallMs / 1000.0)).append('s')
        sb.append(" up=").append(fmt1(report.uptimeMs / 1000.0)).append('s')
        sb.append(" scan=").append(report.scanMs).append("ms")
        sb.append(" thr=").append(report.threadCount)
        sb.append(" top=[")
        report.ranked.forEachIndexed { i, t ->
            if (i > 0) sb.append(", ")
            sb.append(t.tid).append(' ').append(t.name).append(' ').append(t.state).append(' ')
                .append(ticksToMs(t.deltaTicks, clkTck)).append("ms")
        }
        sb.append(']')
        if (report.created.isNotEmpty() || report.exited.isNotEmpty()) {
            sb.append(" churn=+").append(report.created.size)
            appendTids(sb, report.created)
            sb.append(" -").append(report.exited.size)
            appendTids(sb, report.exited)
        }
        if (report.unattributedTicks > 0) {
            sb.append(" unattributed=").append(ticksToMs(report.unattributedTicks, clkTck)).append("ms")
        }
        if (report.noBaseline > 0) sb.append(" noBaseline=").append(report.noBaseline)
        sb.append(blindSpots(report.readErrors, report.badStats, report.listFailed))
        if (isSlowScan(report.scanMs)) sb.append(" SLOW_SCAN")
        if (isSlowScan(report.prevScanMs)) sb.append(" SLOW_BASELINE(").append(report.prevScanMs).append("ms)")
        return sb.toString()
    }

    private fun blindSpots(readErrors: Int, badStats: Int, listFailed: Boolean): String {
        val sb = StringBuilder()
        if (readErrors > 0) sb.append(" readErr=").append(readErrors)
        if (badStats > 0) sb.append(" badStat=").append(badStats)
        if (listFailed) sb.append(" TASK_LIST_FAILED")
        return sb.toString()
    }

    private fun appendTids(sb: StringBuilder, threads: List<ThreadStat>) {
        if (threads.isEmpty()) return
        sb.append('[')
        threads.take(MAX_CHURN_LISTED).forEachIndexed { i, t ->
            if (i > 0) sb.append(", ")
            sb.append(t.tid).append(' ').append(t.name)
        }
        if (threads.size > MAX_CHURN_LISTED) sb.append(", ...")
        sb.append(']')
    }

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** The kernel keeps at most this many bytes of a thread's name as its comm. */
    const val COMM_MAX_LEN = 15

    /**
     * The /proc comm ART gives a Java thread named [javaName] (`SetThreadName`
     * in art/runtime/thread.cc): a name over [COMM_MAX_LEN] bytes keeps its
     * LAST 15 bytes if it contains a '.' and no '@' (so `OkHttp https://host.example/...`
     * keeps its tail), otherwise its FIRST 15 (`DefaultDispatcher-worker-3` →
     * `DefaultDispatch`). ART works on the UTF-8 bytes; the cut slice is decoded
     * the same way the stat line is, so a name cut inside a multi-byte
     * character may not compare equal — it then reads as unmatched, never as
     * a wrong thread.
     */
    fun commForJavaName(javaName: String): String {
        val bytes = javaName.toByteArray(Charsets.UTF_8)
        if (bytes.size <= COMM_MAX_LEN) return javaName
        val keepTail = javaName.contains('.') && !javaName.contains('@')
        val from = if (keepTail) bytes.size - COMM_MAX_LEN else 0
        return String(bytes, from, COMM_MAX_LEN, Charsets.UTF_8)
    }

    /** Whether a Java thread name could be the /proc comm [comm] (see [commForJavaName]). */
    fun javaNameMatchesComm(javaName: String, comm: String): Boolean = commForJavaName(javaName) == comm

    /**
     * The Java threads in [threads] that could be the /proc thread [tid] with
     * comm [comm]. The main thread's tid is the [pid] and its comm is the
     * (truncated) process name, not its Java name `main`, so it is matched by
     * identity with [mainThread] instead; every other thread by name.
     */
    fun <T> javaCandidates(
        tid: Int,
        comm: String,
        pid: Int,
        mainThread: T?,
        threads: Collection<T>,
        nameOf: (T) -> String
    ): List<T> = if (tid == pid && mainThread != null) {
        threads.filter { it == mainThread }
    } else {
        threads.filter { javaNameMatchesComm(nameOf(it), comm) }
    }
}
