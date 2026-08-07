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

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WHY THE PREVIOUS PROCESS DIED — written into the app log at every launch.
 *
 * The support report (`ContactSupportViewModel.createReport`, which attaches
 * `files/log/wallet.log`) is frequently the ONLY channel we have: no adb, no
 * Crashlytics stack, and — for a process that is reaped rather than crashing —
 * NOTHING AT ALL at the end of the previous session's log. A tester whose app
 * dies every ~19 minutes produces a log that simply stops mid-line, which is
 * indistinguishable between the low-memory killer, an ANR kill, a native
 * crash, and the user swiping the app away.
 *
 * Android records the answer itself: `ActivityManager
 * .getHistoricalProcessExitReasons()` (API 30+) hands back the system's own
 * post-mortem for our recent processes — reason, description, death timestamp,
 * PSS/RSS at death, and the process importance at the moment it was killed.
 * [logRecentExits] pulls the last few records and writes them into the log of
 * the NEXT launch, so the evidence rides along in the support report.
 *
 * Where [StartupBreadcrumbs] answers "how far did the dying launch get", this
 * answers "what killed it" — the two are read together.
 *
 * Design constraints, all load-bearing:
 *  - **Never throws into the caller.** Every entry point catches `Throwable`.
 *    This is a diagnostic channel and must never take a launch down.
 *  - **Off the main thread.** The binder call walks the system's exit history;
 *    it runs on a short-lived low-priority daemon thread so it cannot extend
 *    `Application.onCreate` (a daemon thread also can't hold the process open).
 *  - **Called EARLY, but after logging is up** — output written before the
 *    logback file appender starts would be lost.
 *  - **Formatting is pure.** [reasonName], [importanceName], [formatRecord] and
 *    [summaryLine] take primitives only, never `ApplicationExitInfo` (which
 *    cannot be constructed in a JVM unit test), so they are directly testable.
 */
object ProcessExitReasons {

    private val log = LoggerFactory.getLogger(ProcessExitReasons::class.java)

    /**
     * The distinct, greppable prefix of the one-line verdict for the MOST
     * RECENT death. Everything else is context; this is the line to search for.
     */
    const val SUMMARY_PREFIX = "PROCESS EXIT REASON:"

    /**
     * How many historical records to pull. A handful (rather than just the
     * latest) is what makes a repeating kill pattern — e.g. a death every
     * ~19 minutes — visible from a single launch's log, while staying cheap.
     */
    const val MAX_RECORDS = 5

    // ── ApplicationExitInfo.REASON_* ──────────────────────────────────
    // Mirrored as literals rather than referenced, so the mapping is pure
    // (unit-testable on a plain JVM) and unknown FUTURE values still print.
    private const val REASON_UNKNOWN = 0
    private const val REASON_EXIT_SELF = 1
    private const val REASON_SIGNALED = 2
    private const val REASON_LOW_MEMORY = 3
    private const val REASON_CRASH = 4
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6
    private const val REASON_INITIALIZATION_FAILURE = 7
    private const val REASON_PERMISSION_CHANGE = 8
    private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    private const val REASON_USER_REQUESTED = 10
    private const val REASON_USER_STOPPED = 11
    private const val REASON_DEPENDENCY_DIED = 12
    private const val REASON_OTHER = 13
    private const val REASON_FREEZER = 14
    private const val REASON_PACKAGE_STATE_CHANGE = 15
    private const val REASON_PACKAGE_UPDATED = 16

    // ── ActivityManager.RunningAppProcessInfo.IMPORTANCE_* ────────────
    private const val IMPORTANCE_FOREGROUND = 100
    private const val IMPORTANCE_FOREGROUND_SERVICE = 125
    private const val IMPORTANCE_PERCEPTIBLE_PRE_26 = 130
    private const val IMPORTANCE_TOP_SLEEPING_PRE_28 = 150
    private const val IMPORTANCE_CANT_SAVE_STATE_PRE_26 = 170
    private const val IMPORTANCE_VISIBLE = 200
    private const val IMPORTANCE_PERCEPTIBLE = 230
    private const val IMPORTANCE_SERVICE = 300
    private const val IMPORTANCE_TOP_SLEEPING = 325
    private const val IMPORTANCE_CANT_SAVE_STATE = 350
    private const val IMPORTANCE_CACHED = 400
    private const val IMPORTANCE_GONE = 1000

    // ── Entry point ───────────────────────────────────────────────────

    /**
     * Read the system's exit history for this package and write it into the
     * log, OFF the calling thread. Call as early in startup as possible AFTER
     * logging is initialized. Returns immediately; never throws.
     */
    @JvmStatic
    @JvmOverloads
    fun logRecentExits(context: Context, maxRecords: Int = MAX_RECORDS) {
        try {
            val appContext = context.applicationContext ?: context
            val thread = Thread({ collectAndLog(appContext, maxRecords) }, "process-exit-reasons")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY
            thread.start()
        } catch (t: Throwable) {
            // Diagnostics must never cost a launch.
            logQuietly(t)
        }
    }

    private fun collectAndLog(context: Context, maxRecords: Int) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                log.info("$SUMMARY_PREFIX unavailable (needs API 30, this device is API {})", Build.VERSION.SDK_INT)
                return
            }
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) {
                log.info("$SUMMARY_PREFIX unavailable (no ActivityManager)")
                return
            }
            // pid = 0 → every recent process of this package, newest first.
            val records = activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                maxRecords.coerceAtLeast(1)
            )
            if (records.isNullOrEmpty()) {
                log.info("$SUMMARY_PREFIX none recorded (fresh install, or the history was cleared)")
                return
            }
            val now = System.currentTimeMillis()
            records.forEachIndexed { index, info ->
                val line = try {
                    formatRecord(
                        index = index,
                        reason = info.reason,
                        description = info.description,
                        timestampMs = info.timestamp,
                        nowMs = now,
                        pssKb = info.pss,
                        rssKb = info.rss,
                        importance = info.importance,
                        processName = info.processName,
                        pid = info.pid,
                        status = info.status,
                        definingUid = info.definingUid
                    )
                } catch (t: Throwable) {
                    "#$index (unreadable: $t)"
                }
                log.info("STARTUP exit history: {}", line)
            }
            val latest = records[0]
            log.info(
                "$SUMMARY_PREFIX {}",
                summaryLine(
                    reason = latest.reason,
                    description = latest.description,
                    timestampMs = latest.timestamp,
                    nowMs = now,
                    pssKb = latest.pss,
                    rssKb = latest.rss,
                    importance = latest.importance
                )
            )
        } catch (t: Throwable) {
            logQuietly(t)
        }
    }

    private fun logQuietly(t: Throwable) {
        try {
            log.debug("process exit reasons unavailable", t)
        } catch (ignored: Throwable) {
            // nothing left to do — silence is the contract
        }
    }

    // ── Pure formatting (unit-tested) ─────────────────────────────────

    /**
     * `ApplicationExitInfo.REASON_*` → name. Unknown/future values render as
     * `REASON_<int>` so a newer Android's reason is never silently lost.
     */
    @JvmStatic
    internal fun reasonName(reason: Int): String = when (reason) {
        REASON_UNKNOWN -> "UNKNOWN"
        REASON_EXIT_SELF -> "EXIT_SELF"
        REASON_SIGNALED -> "SIGNALED"
        REASON_LOW_MEMORY -> "LOW_MEMORY_LMK"
        REASON_CRASH -> "CRASH"
        REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        REASON_ANR -> "ANR"
        REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        REASON_USER_REQUESTED -> "USER_REQUESTED"
        REASON_USER_STOPPED -> "USER_STOPPED"
        REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    /**
     * `RunningAppProcessInfo.IMPORTANCE_*` → name: WHAT THE PROCESS WAS DOING
     * when it died. A LOW_MEMORY kill at CACHED is ordinary housekeeping; the
     * same kill at FOREGROUND is a real user-visible defect.
     */
    @JvmStatic
    internal fun importanceName(importance: Int): String = when (importance) {
        IMPORTANCE_FOREGROUND -> "FOREGROUND"
        IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        IMPORTANCE_PERCEPTIBLE_PRE_26 -> "PERCEPTIBLE_PRE_26"
        IMPORTANCE_TOP_SLEEPING_PRE_28 -> "TOP_SLEEPING_PRE_28"
        IMPORTANCE_CANT_SAVE_STATE_PRE_26 -> "CANT_SAVE_STATE_PRE_26"
        IMPORTANCE_VISIBLE -> "VISIBLE"
        IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        IMPORTANCE_SERVICE -> "SERVICE"
        IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
        IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
        IMPORTANCE_CACHED -> "CACHED"
        IMPORTANCE_GONE -> "GONE"
        else -> "IMPORTANCE_$importance"
    }

    /** One full record. All inputs are primitives — see the class doc. */
    @JvmStatic
    @Suppress("LongParameterList")
    internal fun formatRecord(
        index: Int,
        reason: Int,
        description: String?,
        timestampMs: Long,
        nowMs: Long,
        pssKb: Long,
        rssKb: Long,
        importance: Int,
        processName: String?,
        pid: Int,
        status: Int,
        definingUid: Int
    ): String = buildString {
        append('#').append(index)
        append(" reason=").append(reasonName(reason)).append('(').append(reason).append(')')
        append(" at=").append(formatTimestamp(timestampMs))
        append(" (").append(formatAge(nowMs - timestampMs)).append(')')
        append(" importance=").append(importanceName(importance)).append('(').append(importance).append(')')
        append(" pss=").append(formatKb(pssKb))
        append(" rss=").append(formatKb(rssKb))
        append(" process=").append(processName ?: "?")
        append(" pid=").append(pid)
        append(" status=").append(status)
        append(" definingUid=").append(definingUid)
        append(" description=").append(description?.let { "\"$it\"" } ?: "none")
    }

    /** The greppable one-liner for the MOST RECENT death. */
    @JvmStatic
    internal fun summaryLine(
        reason: Int,
        description: String?,
        timestampMs: Long,
        nowMs: Long,
        pssKb: Long,
        rssKb: Long,
        importance: Int
    ): String = buildString {
        append(reasonName(reason)).append('(').append(reason).append(')')
        append(" — previous process died ").append(formatAge(nowMs - timestampMs))
        append(" at ").append(formatTimestamp(timestampMs))
        append(", importance=").append(importanceName(importance)).append('(').append(importance).append(')')
        append(", pss=").append(formatKb(pssKb))
        append(", rss=").append(formatKb(rssKb))
        append(", description=").append(description?.let { "\"$it\"" } ?: "none")
    }

    /** `123456KB/120.6MB` — `getPss()`/`getRss()` are in KILOBYTES. */
    @JvmStatic
    internal fun formatKb(kb: Long): String =
        String.format(Locale.US, "%dKB/%.1fMB", kb, kb / 1024.0)

    /** ISO-ish LOCAL time — matched against the tester's wall clock. */
    @JvmStatic
    internal fun formatTimestamp(timestampMs: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(timestampMs))
    } catch (t: Throwable) {
        timestampMs.toString()
    }

    /** Human age of a death: `42m ago`, `3h 12m ago`, `2d 3h ago`. */
    @JvmStatic
    internal fun formatAge(ageMs: Long): String {
        if (ageMs < 0) return "in the future (clock changed)"
        val seconds = ageMs / 1000
        if (seconds < 60) return "${seconds}s ago"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m ago"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m ago"
        val days = hours / 24
        return "${days}d ${hours % 24}h ago"
    }
}
