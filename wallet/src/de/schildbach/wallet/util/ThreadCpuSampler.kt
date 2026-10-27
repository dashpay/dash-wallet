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

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import de.schildbach.wallet.AppForegroundMonitor
import de.schildbach.wallet.util.ThreadCpuStats.Level
import de.schildbach.wallet.util.ThreadCpuStats.Snapshot
import de.schildbach.wallet.util.ThreadCpuStats.ThreadStat
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WHICH THREAD IS BURNING CPU WHILE THE APP IS IN THE BACKGROUND — a
 * per-thread CPU sampler that writes into wallet.log.
 *
 * QA (12.0.1-qa01, 2026-10-26) saw the cached process killed with
 * `EXCESSIVE_RESOURCE_USAGE` ("excessive cpu 569680 during 300375" — about
 * two cores busy for five minutes) an hour after the blockchain service had
 * stopped, with NOTHING in wallet.log, the Rust SDK logs or logcat in
 * between. [ProcessExitReasons] tells us THAT it happened; this tells us
 * WHICH thread: every [SAMPLE_INTERVAL_MS] it reads
 * `/proc/self/task/<tid>/stat` for every thread plus `/proc/self/stat`, and
 * logs the process CPU% and the top threads by CPU over the interval.
 *
 * Greppable prefix: [PREFIX]. On a hot interval (process over
 * [ThreadCpuStats.HOT_PERCENT_OF_ONE_CORE] of one core) the line is WARN and
 * each hot thread also gets its kernel wait channel and — if a Java thread of
 * that name exists — one Java stack. A hot thread with NO Java match is a
 * native (Rust/C++) thread, which is itself the answer we need.
 *
 * Design constraints:
 *  - **Background only.** Starts when [AppForegroundMonitor] says no activity
 *    is visible, stops when one is. Independent of the blockchain service: the
 *    interesting window is after the service stops and the process is cached.
 *  - **Never keeps the process alive.** A coroutine on [Dispatchers.IO]
 *    (daemon threads) using `delay` — no wakelock, no service, no alarm. A
 *    frozen cached process simply doesn't sample; the `wall=` gap on the next
 *    line makes the freeze visible.
 *  - **Cheap.** One small read per thread per minute; the expensive
 *    `Thread.getAllStackTraces()` runs only on a hot interval.
 *  - **Quiet when idle.** Idle intervals log at DEBUG, with one INFO line at
 *    most every [IDLE_INFO_INTERVAL_MS] as a heartbeat.
 *  - **Never throws into the app.** Every read and every log path is guarded;
 *    a thread vanishing mid-scan is normal and just skipped.
 *  - **Testnet/QA only.** Enabled for every flavor except `prod` (the same
 *    predicate as `Constants.IS_PROD_BUILD`, checked via [BuildConfig.FLAVOR]
 *    directly so starting it early in `Application.onCreate` does not force
 *    `Constants`' static initializer — network parameters et al. — earlier
 *    than it runs today).
 *
 * All parsing/diffing/decisions live in [ThreadCpuStats] (pure, unit-tested).
 */
object ThreadCpuSampler {
    private val log = LoggerFactory.getLogger(ThreadCpuSampler::class.java)

    const val PREFIX = "CPU SAMPLE"
    const val SAMPLE_INTERVAL_MS = 60_000L
    const val IDLE_INFO_INTERVAL_MS = 10 * 60_000L

    /** Wall gap above this many intervals is flagged as a freeze/sleep. */
    private const val GAP_FACTOR = 2

    /** Hot threads that get a wchan + Java stack dump per interval. */
    private const val MAX_DETAILED_THREADS = 3
    private const val MAX_STACK_FRAMES = 30

    /** Stat lines are ~300 bytes; comm is at most 15. */
    private const val STAT_BUFFER_BYTES = 1024

    private const val FALLBACK_CLK_TCK = 100L

    private val started = AtomicBoolean(false)

    @JvmStatic
    val isEnabled: Boolean
        get() = BuildConfig.FLAVOR != "prod"

    /** Idempotent. Call once from `Application.onCreate`, after logging is up. */
    @JvmStatic
    fun start() {
        try {
            if (!isEnabled || !started.compareAndSet(false, true)) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ThreadCpuSampler"))
            scope.launch {
                AppForegroundMonitor.isForeground.collectLatest { foreground ->
                    if (!foreground) {
                        runSession()
                    }
                }
            }
        } catch (t: Throwable) {
            logQuietly("start failed", t)
        }
    }

    /** Samples until cancelled by the app coming to the foreground. */
    private suspend fun runSession() {
        val session = Session()
        val baseline = session.snapshot()
        if (baseline == null) {
            log.info("$PREFIX unavailable: /proc/self not readable")
            return
        }
        log.info(
            "$PREFIX started (app not in foreground) threads={} interval={}s clkTck={}",
            baseline.threads.size,
            SAMPLE_INTERVAL_MS / 1000,
            session.clkTck
        )
        var prev: Snapshot = baseline
        var lastInfoMs: Long? = null
        var samples = 0
        try {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                val curr = session.snapshot() ?: continue
                samples++
                lastInfoMs = session.report(prev, curr, lastInfoMs)
                prev = curr
            }
        } finally {
            // Cancellation (foreground) lands here; plain non-suspending logging is fine.
            try {
                log.info("$PREFIX stopped (app in foreground) after {} samples", samples)
            } catch (_: Throwable) {
            }
        }
    }

    /** Per-background-session state: a reused read buffer and the tick rate. */
    private class Session {
        val clkTck: Long = try {
            Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: FALLBACK_CLK_TCK
        } catch (_: Throwable) {
            FALLBACK_CLK_TCK
        }
        private val buffer = ByteArray(STAT_BUFFER_BYTES)
        private val taskDir = File("/proc/self/task")

        fun snapshot(): Snapshot? = try {
            val wall = SystemClock.elapsedRealtime()
            val up = SystemClock.uptimeMillis()
            val process = readFile("/proc/self/stat")?.let(ThreadCpuStats::parseStatLine)
            if (process == null) {
                null
            } else {
                val tids = taskDir.list() ?: emptyArray()
                val threads = HashMap<Int, ThreadStat>(tids.size * 2)
                for (tid in tids) {
                    // A thread that exited between list() and read is simply skipped.
                    val stat = readFile("/proc/self/task/$tid/stat")?.let(ThreadCpuStats::parseStatLine)
                    if (stat != null) threads[stat.tid] = stat
                }
                Snapshot(wall, up, process.ticks, threads)
            }
        } catch (t: Throwable) {
            logQuietly("snapshot failed", t)
            null
        }

        /** Logs one interval; returns the updated last-INFO time. */
        fun report(prev: Snapshot, curr: Snapshot, lastInfoMs: Long?): Long? {
            return try {
                val report = ThreadCpuStats.diff(prev, curr)
                val now = curr.elapsedRealtimeMs
                val level = ThreadCpuStats.levelFor(report, clkTck, now, lastInfoMs, IDLE_INFO_INTERVAL_MS)
                val gap = if (report.wallMs > GAP_FACTOR * SAMPLE_INTERVAL_MS) " GAP(frozen/asleep)" else ""
                val line = "$PREFIX ${ThreadCpuStats.formatLine(report, clkTck)}$gap"
                when (level) {
                    Level.WARN -> {
                        log.warn("$line HOT")
                        ThreadCpuStats.hotThreads(report, clkTck)
                            .take(MAX_DETAILED_THREADS)
                            .let { if (it.isNotEmpty()) logHotDetails(it, report.wallMs) }
                        now
                    }
                    Level.INFO -> {
                        log.info(line)
                        now
                    }
                    Level.DEBUG -> {
                        log.debug(line)
                        lastInfoMs
                    }
                }
            } catch (t: Throwable) {
                logQuietly("report failed", t)
                lastInfoMs
            }
        }

        private fun logHotDetails(hot: List<ThreadCpuStats.ThreadDelta>, wallMs: Long) {
            val javaThreads: Map<Thread, Array<StackTraceElement>> = try {
                Thread.getAllStackTraces()
            } catch (t: Throwable) {
                emptyMap()
            }
            for (t in hot) {
                val pct = ThreadCpuStats.cpuPercent(t.deltaTicks, clkTck, wallMs)
                val wchan = readFile("/proc/self/task/${t.tid}/wchan")?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "0" } ?: "?"
                val candidates = javaThreads.entries.filter {
                    ThreadCpuStats.javaNameMatchesComm(it.key.name, t.name)
                }
                if (candidates.isEmpty()) {
                    log.warn(
                        "$PREFIX hot tid={} name='{}' state={} cpu={}% wchan={} java=NONE (native/unattached thread)",
                        t.tid, t.name, t.state, "%.1f".format(java.util.Locale.US, pct), wchan
                    )
                    continue
                }
                // Same-named Java threads (pools) can't be told apart by tid from
                // Java; prefer one that is RUNNABLE, since a CPU-burner usually is.
                val pick = candidates.firstOrNull { it.key.state == Thread.State.RUNNABLE } ?: candidates.first()
                val stack = StringBuilder()
                pick.value.take(MAX_STACK_FRAMES).forEach { stack.append("\n    at ").append(it) }
                if (pick.value.size > MAX_STACK_FRAMES) stack.append("\n    ...")
                log.warn(
                    "$PREFIX hot tid={} name='{}' state={} cpu={}% wchan={} java='{}' ({} of {} same-name, javaState={}){}",
                    t.tid, t.name, t.state, "%.1f".format(java.util.Locale.US, pct), wchan,
                    pick.key.name, candidates.indexOf(pick) + 1, candidates.size, pick.key.state, stack
                )
            }
        }

        /** Reads a small /proc file into the reused buffer; null if gone/unreadable. */
        private fun readFile(path: String): String? = try {
            FileInputStream(path).use { input ->
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n <= 0) break
                    total += n
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun logQuietly(what: String, t: Throwable) {
        try {
            log.debug("$PREFIX {}: {}", what, t.toString())
        } catch (_: Throwable) {
        }
    }
}
