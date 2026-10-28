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
import de.schildbach.wallet.util.ThreadCpuStats.ScanTally
import de.schildbach.wallet.util.ThreadCpuStats.Snapshot
import de.schildbach.wallet.util.ThreadCpuStats.ThreadStat
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * that name exists — one Java stack. A hot thread with NO Java match is
 * `java=UNMATCHED`: most likely a native (Rust/C++) thread, but a Java thread
 * that exited or was renamed after the scan looks the same, so it is not
 * labelled native.
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
 *    most every [IDLE_INFO_INTERVAL_MS] as a heartbeat. An idle sample whose
 *    scan was degraded (`readErr=` etc.) or whose span shows a freeze (`GAP`)
 *    is still INFO, and like any INFO line it restarts the heartbeat clock.
 *  - **Every signal reaches wallet.log.** The root logger is INFO, so nothing
 *    the sampler exists to surface is logged at DEBUG: only clean idle samples
 *    between heartbeats are. A failure that could repeat every interval (an
 *    unreadable `/proc/self/stat`, a report that throws) is INFO but rate
 *    limited by [ThreadCpuStats.FailureThrottle]: the first, then one per
 *    [IDLE_INFO_INTERVAL_MS], and a `recovered` line with the count after.
 *  - **Never throws into the app.** Every read and every log path is guarded,
 *    and the scope's [CoroutineExceptionHandler] catches anything that still
 *    escapes; a thread vanishing mid-scan is normal and just skipped. Reads
 *    that fail for any OTHER reason are counted onto the line (`readErr=`
 *    etc., see [ThreadCpuStats.formatLine]) so a blind scan is visible.
 *  - **Stops promptly.** Returning to the foreground cancels the session; a
 *    scan in progress checks for cancellation before each thread and is
 *    discarded rather than logged.
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
            // Last line of defence: anything escaping the guarded paths below is
            // logged here instead of reaching the default (crashing) handler.
            val handler = CoroutineExceptionHandler { _, t -> logQuietly("sampler failed", t) }
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineName("ThreadCpuSampler") + handler
            )
            scope.launch {
                AppForegroundMonitor.isForeground.collectLatest { foreground ->
                    if (!foreground) {
                        try {
                            runSession()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            // Contained per session: the next background period starts afresh.
                            logQuietly("session failed", t)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            logQuietly("start failed", t)
        }
    }

    /** Samples until cancelled by the app coming to the foreground. */
    private suspend fun runSession() {
        var announced = false
        var samples = 0
        try {
            val session = Session()
            // A failed baseline is retried each interval: foreground state won't
            // re-emit, so returning here would end sampling for this whole period.
            var baseline = session.snapshot()
            while (baseline == null) {
                currentCoroutineContext().ensureActive()
                session.scanFailed("no baseline yet, retrying")
                delay(SAMPLE_INTERVAL_MS)
                baseline = session.snapshot()
            }
            currentCoroutineContext().ensureActive()
            session.scanSucceeded()
            log.info(
                "$PREFIX started (app not in foreground) threads={} interval={}s clkTck={}",
                baseline.threads.size,
                SAMPLE_INTERVAL_MS / 1000,
                session.clkTck
            )
            announced = true
            var prev: Snapshot = baseline
            var lastInfoMs: Long? = null
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                val curr = session.snapshot()
                // A scan that finished just as the app came forward is stale: drop it.
                currentCoroutineContext().ensureActive()
                if (curr == null) {
                    // `prev` stays the baseline: the next good sample spans the gap.
                    session.scanFailed("keeping last good sample as baseline")
                    continue
                }
                val skipped = session.scanSucceeded()
                samples++
                lastInfoMs = session.report(prev, curr, lastInfoMs, SAMPLE_INTERVAL_MS * (1 + skipped))
                prev = curr
            }
        } finally {
            // Cancellation (foreground) lands here; plain non-suspending logging is fine.
            if (announced) {
                try {
                    log.info("$PREFIX stopped (app in foreground) after {} samples", samples)
                } catch (_: Throwable) {
                }
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

        /** Why the last [snapshot] returned null; logged by the caller. */
        var lastFailure: String? = null
            private set

        private val scanFailures = ThreadCpuStats.FailureThrottle(IDLE_INFO_INTERVAL_MS)
        private val threadReadFailures = ThreadCpuStats.FailureThrottle(IDLE_INFO_INTERVAL_MS)
        private val reportFailures = ThreadCpuStats.FailureThrottle(IDLE_INFO_INTERVAL_MS)

        /** A scan failed: INFO for the first, then once per heartbeat interval. */
        fun scanFailed(note: String) {
            try {
                if (scanFailures.onFailure(SystemClock.elapsedRealtime())) {
                    log.info(
                        "$PREFIX unavailable: {} failures={} ({})",
                        lastFailure ?: "?",
                        scanFailures.failures,
                        note
                    )
                }
            } catch (_: Throwable) {
            }
        }

        /** A scan succeeded; returns how many failed scans it ends (logged at INFO if any). */
        fun scanSucceeded(): Int {
            val skipped = scanFailures.onSuccess()
            if (skipped > 0) {
                try {
                    log.info("$PREFIX recovered after {} failed scans", skipped)
                } catch (_: Throwable) {
                }
            }
            return skipped
        }

        /**
         * Reads the process and every thread; null (with [lastFailure] set) if
         * the process line itself is unreadable. Throws [CancellationException]
         * if cancelled mid-scan, so a partial scan is never reported.
         */
        suspend fun snapshot(): Snapshot? = try {
            lastFailure = null
            val wall = SystemClock.elapsedRealtime()
            val up = SystemClock.uptimeMillis()
            val tally = ScanTally()
            val processLine = readFile("/proc/self/stat", tally)
            val process = processLine?.let(ThreadCpuStats::parseStatLine)
            if (process == null) {
                val why = tally.firstError?.toString() ?: if (processLine == null) "unreadable" else "unparsable"
                lastFailure = "/proc/self/stat $why"
                null
            } else {
                // list() returns null (or throws) when the dir can't be read: that's
                // blindness, flagged on the line, not a process with no threads.
                val tids = try {
                    taskDir.list()
                } catch (t: Throwable) {
                    tally.listFailed(t)
                    null
                } ?: run {
                    if (!tally.listFailed) tally.listFailed(null)
                    emptyArray()
                }
                val threads = HashMap<Int, ThreadStat>(tids.size * 2)
                val ctx = currentCoroutineContext()
                for (tid in tids) {
                    ctx.ensureActive()
                    // A thread that exited between list() and read is simply skipped;
                    // any other failure or a rejected line is counted in `tally`.
                    val stat = readFile("/proc/self/task/$tid/stat", tally)?.let(tally::parse)
                    if (stat != null) threads[stat.tid] = stat
                }
                // `wall` was taken just before the first read, so this spans every read.
                val scanMs = SystemClock.elapsedRealtime() - wall
                // The counts are on the sample line (INFO); the cause goes out with
                // it, but rate limited, since an EACCES would recur every scan.
                val firstError = tally.firstError
                if (firstError == null) {
                    threadReadFailures.onSuccess()
                } else if (threadReadFailures.onFailure(wall)) {
                    logQuietly("unreadable thread stats (first error)", firstError)
                }
                Snapshot(wall, up, process.ticks, threads, scanMs, tally.readErrors, tally.badStats, tally.listFailed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastFailure = "snapshot failed: $t"
            null
        }

        /**
         * Logs one interval; returns the updated last-INFO time. [expectedWallMs]
         * is the span the interval should have (longer after failed scans).
         */
        fun report(prev: Snapshot, curr: Snapshot, lastInfoMs: Long?, expectedWallMs: Long): Long? {
            return try {
                val report = ThreadCpuStats.diff(prev, curr)
                val now = curr.elapsedRealtimeMs
                val level =
                    ThreadCpuStats.levelFor(report, clkTck, now, lastInfoMs, IDLE_INFO_INTERVAL_MS, expectedWallMs)
                val gap = if (ThreadCpuStats.isGap(report.wallMs, expectedWallMs)) " GAP(frozen/asleep)" else ""
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
                }.also { reportFailures.onSuccess() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                try {
                    if (reportFailures.onFailure(SystemClock.elapsedRealtime())) {
                        logQuietly("report failed (failures=${reportFailures.failures})", t)
                    }
                } catch (_: Throwable) {
                }
                lastInfoMs
            }
        }

        private fun logHotDetails(hot: List<ThreadCpuStats.ThreadDelta>, wallMs: Long) {
            // Set when the stacks couldn't be taken: then every thread would read
            // as unmatched, so the line says the stacks failed instead.
            var stacksError: Throwable? = null
            val javaThreads: Map<Thread, Array<StackTraceElement>> = try {
                Thread.getAllStackTraces()
            } catch (t: Throwable) {
                stacksError = t
                emptyMap()
            }
            for (t in hot) {
                val pct = ThreadCpuStats.cpuPercent(t.deltaTicks, clkTck, wallMs)
                val wchan = readFile("/proc/self/task/${t.tid}/wchan")?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "0" } ?: "?"
                if (stacksError != null) {
                    log.warn(
                        "$PREFIX hot tid={} name='{}' state={} cpu={}% wchan={} java=UNKNOWN (stacks failed: {})",
                        t.tid, t.name, t.state, "%.1f".format(java.util.Locale.US, pct), wchan, stacksError.toString()
                    )
                    continue
                }
                val candidates = javaThreads.entries.filter {
                    ThreadCpuStats.javaNameMatchesComm(it.key.name, t.name)
                }
                if (candidates.isEmpty()) {
                    // No positive identity: native, unattached, or a Java thread
                    // that exited/was renamed between the scan and the stacks.
                    log.warn(
                        "$PREFIX hot tid={} name='{}' state={} cpu={}% wchan={} " +
                            "java=UNMATCHED (no live Java thread with this name; native, unattached, " +
                            "or exited/renamed since the scan)",
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

        /**
         * Reads a small /proc file into the reused buffer; null if gone/unreadable.
         * A failure is handed to [tally] (which ignores exited threads) when given.
         */
        private fun readFile(path: String, tally: ScanTally? = null): String? = try {
            FileInputStream(path).use { input ->
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n <= 0) break
                    total += n
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            tally?.readFailed(t)
            null
        }
    }

    /**
     * INFO, never DEBUG: the root logger is INFO, and every caller is a failure
     * the log must show. Callers that could fire every interval rate-limit
     * through [ThreadCpuStats.FailureThrottle] first.
     */
    private fun logQuietly(what: String, t: Throwable) {
        try {
            log.info("$PREFIX {}: {}", what, t.toString())
        } catch (_: Throwable) {
        }
    }
}
