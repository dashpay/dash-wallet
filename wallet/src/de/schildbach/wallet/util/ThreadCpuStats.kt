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
     * @param exited threads present before but gone now (their CPU in the
     *   interval is NOT in [ranked] — it shows up in [unattributedTicks]).
     * @param processDeltaTicks the process total (`/proc/self/stat`), which
     *   includes threads that exited during the interval.
     * @param unattributedTicks process delta minus the sum of [ranked]:
     *   CPU burnt by threads that died before we could see them. A large
     *   value means short-lived thread churn.
     */
    data class IntervalReport(
        val wallMs: Long,
        val uptimeMs: Long,
        val threadCount: Int,
        val processDeltaTicks: Long,
        val ranked: List<ThreadDelta>,
        val created: List<ThreadStat>,
        val exited: List<ThreadStat>,
        val unattributedTicks: Long
    )

    /** A point-in-time view of the process. Times are `SystemClock` millis. */
    data class Snapshot(
        val elapsedRealtimeMs: Long,
        val uptimeMs: Long,
        val processTicks: Long,
        val threads: Map<Int, ThreadStat>
    )

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

    /** Diffs two snapshots into one interval report. */
    fun diff(prev: Snapshot, curr: Snapshot, topN: Int = TOP_N): IntervalReport {
        val created = ArrayList<ThreadStat>()
        val exited = ArrayList<ThreadStat>()
        val deltas = ArrayList<ThreadDelta>(curr.threads.size)
        var attributed = 0L
        for (now in curr.threads.values) {
            val before = prev.threads[now.tid]
            val delta = if (before == null || before.startTicks != now.startTicks) {
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
        for (before in prev.threads.values) {
            if (!curr.threads.containsKey(before.tid)) exited.add(before)
        }
        deltas.sortWith(compareByDescending<ThreadDelta> { it.deltaTicks }.thenBy { it.tid })
        created.sortBy { it.tid }
        exited.sortBy { it.tid }
        val processDelta = (curr.processTicks - prev.processTicks).coerceAtLeast(0)
        return IntervalReport(
            wallMs = curr.elapsedRealtimeMs - prev.elapsedRealtimeMs,
            uptimeMs = curr.uptimeMs - prev.uptimeMs,
            threadCount = curr.threads.size,
            processDeltaTicks = processDelta,
            ranked = if (deltas.size > topN) deltas.subList(0, topN).toList() else deltas,
            created = created,
            exited = exited,
            unattributedTicks = (processDelta - attributed).coerceAtLeast(0)
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

    /**
     * The threads that get a detailed dump (wchan + Java stack) on a hot
     * sample: every thread itself over the threshold, or — if the process is
     * hot only because several threads share the load — the busiest one.
     */
    fun hotThreads(report: IntervalReport, clkTck: Long, thresholdPct: Double = HOT_PERCENT_OF_ONE_CORE): List<ThreadDelta> {
        if (!isHot(report.processDeltaTicks, clkTck, report.wallMs, thresholdPct)) return emptyList()
        val over = report.ranked.filter { isHot(it.deltaTicks, clkTck, report.wallMs, thresholdPct) }
        return over.ifEmpty { report.ranked.take(1) }
    }

    enum class Level { DEBUG, INFO, WARN }

    /**
     * Hot → WARN every time. Active (between idle and hot) → INFO. Idle →
     * INFO at most once per [idleInfoIntervalMs] (so a quiet log still shows
     * the sampler alive), DEBUG otherwise. Thread churn alone does not lift
     * an idle sample.
     */
    fun levelFor(
        report: IntervalReport,
        clkTck: Long,
        nowMs: Long,
        lastInfoMs: Long?,
        idleInfoIntervalMs: Long
    ): Level = when {
        isHot(report.processDeltaTicks, clkTck, report.wallMs) -> Level.WARN
        !isIdle(report.processDeltaTicks, clkTck, report.wallMs) -> Level.INFO
        lastInfoMs == null || nowMs - lastInfoMs >= idleInfoIntervalMs -> Level.INFO
        else -> Level.DEBUG
    }

    /**
     * One compact line, e.g.
     * `proc=187.3% cpu=112400ms wall=60.0s up=60.0s thr=142 top=[12345 tokio-rt R 41230ms, ...] churn=+2[...] -1[...] unattributed=230ms`
     * (the caller adds the greppable prefix). `wall` is elapsedRealtime
     * (counts deep sleep and freezes), `up` is uptime (stops in deep sleep):
     * a wall gap far beyond the interval with `up` keeping pace means the
     * process was frozen while the device was awake.
     */
    fun formatLine(report: IntervalReport, clkTck: Long): String {
        val sb = StringBuilder(256)
        sb.append("proc=").append(fmt1(cpuPercent(report.processDeltaTicks, clkTck, report.wallMs))).append('%')
        sb.append(" cpu=").append(ticksToMs(report.processDeltaTicks, clkTck)).append("ms")
        sb.append(" wall=").append(fmt1(report.wallMs / 1000.0)).append('s')
        sb.append(" up=").append(fmt1(report.uptimeMs / 1000.0)).append('s')
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

    /**
     * Android truncates a thread's comm to 15 bytes, so a Java thread named
     * `DefaultDispatcher-worker-3` appears in /proc as `DefaultDispatch`.
     */
    const val COMM_MAX_LEN = 15

    /** Whether a Java thread name could be the /proc comm [comm]. */
    fun javaNameMatchesComm(javaName: String, comm: String): Boolean =
        javaName == comm || (comm.length == COMM_MAX_LEN && javaName.startsWith(comm))
}
