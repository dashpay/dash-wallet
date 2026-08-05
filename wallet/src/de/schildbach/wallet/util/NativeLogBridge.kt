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

import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Copies the Rust/SDK `log::` output out of logcat and into the app's OWN
 * file logger, so it rides along in the in-app "Report an issue" upload.
 *
 * ## Why this exists
 *
 * The native SDK installs `android_logger` in `JNI_OnLoad`
 * (`packages/rs-unified-sdk-jni/src/lib.rs`) with
 * `Config::default().with_max_level(Info).with_tag("DashSDK")`. That forwards
 * the whole Rust `log` facade to **logcat and nowhere else**. The app's own
 * pipeline is a separate logback [ch.qos.logback.core.rolling.RollingFileAppender]
 * writing `files/log/wallet.log` (see `WalletApplication.initLogging`), and
 * `wallet.log` is what the support report attaches. Net effect before this
 * bridge: a remote tester's uploaded report contained ZERO Rust lines, so the
 * SDK's runtime behaviour was invisible for anyone we cannot run `adb logcat`
 * against — which is every real tester. Concretely this is what makes the
 * watermark diagnostics (`wallet-event batch: ... synced_height_persisted=...`
 * per drain, and the one-shot `SYNC WATERMARK FROZEN` error) reportable.
 *
 * ## How
 *
 * An app may read its OWN process's logcat entries with no permission at all:
 * `READ_LOGS` is only required to see OTHER apps' entries — `logd` filters a
 * plain reader's view down to its own UID. So a periodic
 * `logcat -d -v threadtime --pid=<self> DashSDK:V *:S` returns exactly the
 * native lines and nothing else, on every API level this app supports
 * (minSdk 29 / targetSdk 35; `--pid` exists since API 24). Each new line is
 * re-emitted verbatim through slf4j on the [EMITTER_LOGGER_NAME] logger, so it
 * lands in `wallet.log` through the ordinary appender.
 *
 * This is deliberately a *pull* from the circular buffer rather than a one-shot
 * drain at report time: the buffer is small and shared, so a one-shot read at
 * report time would routinely have lost the early one-shot errors — exactly
 * the lines worth reporting. Polling captures them while they are still there.
 * [drainNow] additionally tops up the tail right before a report is built.
 *
 * ## No feedback loop
 *
 * Re-emitted lines go out under the logcat tag [EMITTER_LOGGER_NAME]
 * ("DashSdkNative"), while the filterspec accepts only the exact tag
 * [NATIVE_TAG] ("DashSDK"), so the bridge cannot read its own output back.
 * [Budget.admit] rejects any such line a second time as belt and braces.
 *
 * ## Bounds (all explicit — reports must not balloon)
 *
 * - [MAX_LINES_PER_DRAIN] lines read per poll;
 * - [MAX_LINE_CHARS] characters per line (longer lines are truncated);
 * - [MAX_FORWARDED_LINES] lines / [MAX_FORWARDED_CHARS] characters forwarded
 *   per process lifetime — whichever is hit first, after which only WARN/ERROR
 *   lines continue, capped by a further [ERROR_RESERVE_LINES] reserve. The
 *   worst case a report can gain is therefore about 1 MB.
 *
 * ## Safety
 *
 * The poll runs on one low-priority daemon thread — never a caller's thread,
 * never the main thread — and every `logcat` invocation is killed by a
 * watchdog after [DRAIN_TIMEOUT_MS]. Failures are swallowed: if `logcat` is
 * unavailable or times out repeatedly the bridge disables itself and the
 * report is still generated and uploaded, just without the native lines.
 *
 * Sensitive material is filtered by [isSensitive]. The tag allowlist is
 * already a strong guard (nothing but the SDK's `log` facade uses the DashSDK
 * tag, and it emits at Info and above), but the keyword drop is kept as
 * defence in depth against a future native log line.
 */
object NativeLogBridge {

    private val log = LoggerFactory.getLogger(NativeLogBridge::class.java)

    /** The exact logcat tag `android_logger` is configured with, Rust side. */
    const val NATIVE_TAG = "DashSDK"

    /**
     * Logger name the captured lines are re-emitted under. Deliberately NOT
     * equal to [NATIVE_TAG]: logback's LogcatAppender tags with `%logger{0}`,
     * so a matching name would make the bridge read back its own output.
     */
    const val EMITTER_LOGGER_NAME = "DashSdkNative"

    /** Lines read from the buffer per poll. */
    const val MAX_LINES_PER_DRAIN = 2_000

    /** Per-line truncation. */
    const val MAX_LINE_CHARS = 512

    /** Lifetime line budget before only WARN/ERROR continues. */
    const val MAX_FORWARDED_LINES = 10_000

    /** Lifetime character budget before only WARN/ERROR continues (1 MB). */
    const val MAX_FORWARDED_CHARS = 1024 * 1024

    /** WARN/ERROR lines still forwarded after the main budget is spent. */
    const val ERROR_RESERVE_LINES = 500

    /** Hard kill for a single `logcat` invocation. */
    const val DRAIN_TIMEOUT_MS = 5_000L

    /** Poll interval while the native side is producing output. */
    const val ACTIVE_INTERVAL_MS = 30_000L

    /** Poll interval once it has gone quiet (the SDK may not even be loaded). */
    const val IDLE_INTERVAL_MS = 5 * 60_000L

    /** Empty polls in a row before backing off to [IDLE_INTERVAL_MS]. */
    const val IDLE_AFTER_EMPTY_DRAINS = 10

    /** Consecutive `logcat` failures before the bridge gives up for good. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** Placed where a run of lines was dropped by buffer rollover. */
    const val GAP_MARKER = "--- logcat buffer rolled; earlier native lines were lost ---"

    /**
     * Substrings that must never leave the device in a support report. A line
     * containing any of these (case-insensitive) is dropped entirely rather
     * than partially redacted — the native side has no legitimate Info-level
     * reason to name any of them, so a hit means something went wrong upstream
     * and the whole line is suspect.
     */
    private val SENSITIVE_MARKERS = listOf(
        "mnemonic",
        "seed phrase",
        "seedphrase",
        "recovery phrase",
        "passphrase",
        "private key",
        "private_key",
        "privatekey",
        "privkey",
        "secret key",
        "secret_key",
        "xprv",
        "tprv",
        "wif=",
        "pin=",
        "password"
    )

    /** `MM-DD HH:MM:SS.mmm  pid  tid L TAG: message` — captures `L`. */
    private val THREADTIME_LEVEL =
        Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEFS])\s""")

    private val emitter = LoggerFactory.getLogger(EMITTER_LOGGER_NAME)

    private val budget = Budget()

    /**
     * Serializes whole drains (read + forward) between the poller thread and
     * [drainNow] callers. Without it two concurrent drains interleave on
     * [lastForwarded] and can re-emit or skip lines; with it a forced drain
     * simply runs before or after the in-flight poll. Callers block at most
     * [DRAIN_TIMEOUT_MS] + parse time — acceptable on the background
     * contexts [drainNow] is called from, never the main thread.
     */
    private val drainLock = Any()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var lastForwarded: String? = null

    @Volatile
    private var disabled = false

    private var consecutiveFailures = 0
    private var consecutiveEmptyDrains = 0

    /**
     * Starts the poller. Idempotent — a second call is a no-op. Returns
     * immediately; all work happens on the daemon thread it spawns.
     */
    @Synchronized
    fun start() {
        if (thread != null || disabled) return
        val t = Thread({ runLoop() }, "native-log-bridge")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        thread = t
        t.start()
    }

    /**
     * Drains whatever the native side has logged since the last poll, on the
     * CALLING thread. Called from report generation (already on
     * `Dispatchers.IO`) so the tail of the session makes it into the attached
     * `wallet.log` instead of being up to one poll interval stale, and after
     * the DashPay provisioning pass, whose long native ops used to outlive
     * the 30 s/5 min poll cadence and lose their SDK lines. Bounded by the
     * same timeout and budget as a normal poll, serialized against the
     * poller via [drainLock], and never throws.
     */
    fun drainNow() {
        if (disabled) return
        try {
            synchronized(drainLock) {
                forward(readNativeLines())
            }
        } catch (t: Throwable) {
            log.info("native log drain failed; continuing without it: {}", t.toString())
        }
    }

    private fun runLoop() {
        while (!disabled) {
            val produced = try {
                synchronized(drainLock) {
                    val lines = readNativeLines()
                    consecutiveFailures = 0
                    forward(lines)
                }
            } catch (t: Throwable) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn(
                        "native log bridge disabled after {} failures ({}); reports will not " +
                            "contain SDK native lines",
                        consecutiveFailures,
                        t.toString()
                    )
                    disabled = true
                }
                0
            }

            consecutiveEmptyDrains = if (produced > 0) 0 else consecutiveEmptyDrains + 1
            val interval = if (consecutiveEmptyDrains >= IDLE_AFTER_EMPTY_DRAINS) {
                IDLE_INTERVAL_MS
            } else {
                ACTIVE_INTERVAL_MS
            }
            try {
                Thread.sleep(interval)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Emits the lines the bridge has not seen yet; returns how many it wrote. */
    private fun forward(lines: List<String>): Int {
        if (lines.isEmpty()) return 0
        val (fresh, gap) = newLinesAfter(lines, lastForwarded)
        if (fresh.isEmpty()) return 0
        lastForwarded = fresh.last()
        if (gap) emitter.info(GAP_MARKER)
        var written = 0
        for (line in fresh) {
            val admitted = budget.admit(line) ?: continue
            emitter.info(admitted)
            written++
        }
        if (budget.consumeExhaustionNotice()) {
            emitter.info(
                "--- native log budget spent (${MAX_FORWARDED_LINES} lines / " +
                    "${MAX_FORWARDED_CHARS} chars); only WARN/ERROR continues ---"
            )
        }
        return written
    }

    /**
     * Runs `logcat -d` for this process and this tag only, bounded by
     * [MAX_LINES_PER_DRAIN] and killed after [DRAIN_TIMEOUT_MS].
     */
    @Throws(IOException::class)
    private fun readNativeLines(): List<String> {
        val command = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "--pid=" + android.os.Process.myPid(),
            "-t", MAX_LINES_PER_DRAIN.toString(),
            "$NATIVE_TAG:V",
            "*:S"
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val watchdog = Thread({
            try {
                Thread.sleep(DRAIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            process.destroy()
        }, "native-log-bridge-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        return try {
            val lines = ArrayList<String>(64)
            process.inputStream.bufferedReader().use { reader ->
                while (lines.size < MAX_LINES_PER_DRAIN) {
                    val line = reader.readLine() ?: break
                    lines.add(line)
                }
            }
            process.waitFor()
            lines
        } finally {
            watchdog.interrupt()
            process.destroy()
        }
    }

    // ---------------------------------------------------------------- pure

    /**
     * The lines of [lines] that follow the last one already forwarded, plus a
     * flag saying the marker was not found at all (the circular buffer rolled
     * past it, so an unknown number of lines was lost).
     *
     * The marker is matched at its LAST occurrence, so a line is never emitted
     * twice; the cost is that a run of byte-identical lines — same millisecond,
     * same thread, same text — can lose one copy. Acceptable: `-v threadtime`
     * carries a timestamp and tid, which makes true duplicates vanishingly rare
     * and never load-bearing.
     */
    internal fun newLinesAfter(lines: List<String>, lastForwarded: String?): Pair<List<String>, Boolean> {
        if (lastForwarded == null) return lines to false
        val index = lines.lastIndexOf(lastForwarded)
        if (index < 0) return lines to true
        return lines.subList(index + 1, lines.size).toList() to false
    }

    /** The threadtime level character (`I`, `W`, `E`, …), or null if unparsed. */
    internal fun levelOf(line: String): Char? =
        THREADTIME_LEVEL.find(line)?.groupValues?.get(1)?.firstOrNull()

    /** True once the main budget is spent and only WARN/ERROR should pass. */
    internal fun isHighPriority(line: String): Boolean =
        when (levelOf(line)) {
            'W', 'E', 'F' -> true
            // An unparsed line is treated as high priority: the format changed
            // and dropping everything silently would be worse than keeping it.
            null -> true
            else -> false
        }

    /** Whether the line must be withheld from the report. */
    internal fun isSensitive(line: String): Boolean {
        val lower = line.lowercase()
        return SENSITIVE_MARKERS.any { lower.contains(it) }
    }

    /** Caps a single line at [MAX_LINE_CHARS], marking the cut. */
    internal fun truncate(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…[truncated]"

    /**
     * The lifetime volume cap. Separated from the poller so the whole bounding
     * rule is unit-testable without touching logcat or process state.
     */
    internal class Budget {
        private var lines = 0
        private var chars = 0
        private var reserveUsed = 0
        private var exhaustionPending = false
        private var exhaustionReported = false

        /** The text to write, or null to drop this line. */
        @Synchronized
        fun admit(rawLine: String): String? {
            if (rawLine.isBlank()) return null
            // Never read our own re-emitted output back in (the tag filter
            // already prevents it; this is the second lock on that door).
            if (rawLine.contains("$EMITTER_LOGGER_NAME:")) return null
            if (isSensitive(rawLine)) return null
            val line = truncate(rawLine)

            val spent = lines >= MAX_FORWARDED_LINES || chars >= MAX_FORWARDED_CHARS
            if (spent) {
                if (!exhaustionReported) {
                    exhaustionReported = true
                    exhaustionPending = true
                }
                if (!isHighPriority(line)) return null
                if (reserveUsed >= ERROR_RESERVE_LINES) return null
                reserveUsed++
                return line
            }

            lines++
            chars += line.length
            return line
        }

        /** True exactly once, on the first call after the budget ran out. */
        @Synchronized
        fun consumeExhaustionNotice(): Boolean {
            if (!exhaustionPending) return false
            exhaustionPending = false
            return true
        }
    }
}
