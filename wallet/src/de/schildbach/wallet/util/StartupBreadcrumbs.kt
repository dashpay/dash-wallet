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
import java.io.File

/**
 * PERSISTED launch-stage breadcrumbs — the crash-loop diagnostic channel for
 * installs where neither adb nor a Crashlytics stack is available (the app
 * itself is the only reporting channel).
 *
 * Every launch writes a numbered stage marker to `files/startup.breadcrumbs`
 * as it passes each startup milestone (plain file appends, independent of the
 * logging pipeline, which initializes AFTER the first marks). When a launch
 * dies, the file's last line names the dying stage. On the next launch,
 * [init]:
 *  1. preserves the previous launch's trail to `startup.breadcrumbs.prev`
 *     when that launch never reached [STAGE_MAIN_UI_SHOWN] (so the evidence
 *     of the LAST DEATH always survives exactly one more launch, and rides
 *     along in the support report — see [reportText]);
 *  2. maintains a consecutive-incomplete-launch counter, and advises
 *     SAFE MODE ([isSafeModeAdvised]) after [SAFE_MODE_THRESHOLD]
 *     consecutive launches died before showing the main UI. A safe-mode
 *     launch skips the wallet load and every engine start so the app is
 *     guaranteed to OPEN and offer the crash report
 *     (`OnboardingActivity` shows the support dialog). The counter decays by
 *     one when safe mode fires, so the launch AFTER a safe-mode launch
 *     retries a full normal start — the loop alternates
 *     crash → SAFE (report offered) → retry, never a permanent lock-out.
 *
 * The survival marker ([markLaunchSurvived]) fires [SURVIVAL_DELAY_MS] after
 * the main UI comes up rather than immediately, so a native (Rust/JNI) crash
 * a few seconds into the session — one that leaves NO Java stack trace —
 * still counts as an incomplete launch and eventually trips safe mode.
 *
 * All methods are safe to call before [init] (they no-op) and never throw:
 * this is a diagnostic channel and must never itself take the app down.
 */
object StartupBreadcrumbs {
    // ── Stage numbers: STABLE — they are the support-report vocabulary. ──
    // Synchronous Application.onCreate lane (0-19):
    const val STAGE_APP_ONCREATE = 0
    const val STAGE_LOGGING_INITIALIZED = 1
    const val STAGE_FIREBASE_INITIALIZED = 2
    const val STAGE_CONFIG_LOADED = 3
    const val STAGE_WALLET_LOAD_BEGIN = 4
    const val STAGE_WALLET_PROTOBUF_PARSED = 5
    const val STAGE_WALLET_CONSISTENCY_CHECKED = 6
    const val STAGE_FINALIZE_INIT_BEGIN = 7
    const val STAGE_INIT_DASH_DONE = 8
    const val STAGE_AFTER_LOAD_WALLET_DONE = 9
    const val STAGE_PLATFORM_INIT_KICKED = 10
    const val STAGE_ONCREATE_COMPLETE = 11
    /** The SURVIVAL marker — written [SURVIVAL_DELAY_MS] after the main UI shows. */
    const val STAGE_MAIN_UI_SHOWN = 12

    // Async engine lane (20-39) — may interleave with or follow the UI lane:
    const val STAGE_SDK_BIND_KICKED = 20
    const val STAGE_SDK_L1_ENGINE_STARTING = 21
    const val STAGE_SDK_L1_ENGINE_STARTED = 22
    const val STAGE_CUTOVER_SERVICES_STARTED = 23

    // Degradation markers (90+):
    const val STAGE_WALLET_LOAD_FAILED = 90
    const val STAGE_WALLET_LOAD_SKIPPED_SAFE_MODE = 91
    const val STAGE_DEGRADED = 92
    /** HARD size guard: the wallet file is ≥2GB — unparseable by construction, parse skipped. */
    const val STAGE_WALLET_FILE_OVERSIZE = 93
    /** SOFT size guard: a risky-size parse OOMed and routed to the key-backup recovery. */
    const val STAGE_WALLET_PARSE_OOM_RECOVERED = 94
    /** The key backup itself was missing/unreadable — wallet needs a restore from seed. */
    const val STAGE_WALLET_BACKUP_UNUSABLE = 95
    /** The key-backup recovery SUCCEEDED — small wallet restored, blockchain reset for rescan. */
    const val STAGE_WALLET_RECOVERED_FROM_BACKUP = 96

    /** Consecutive pre-UI deaths before a safe-mode launch is advised. */
    const val SAFE_MODE_THRESHOLD = 2

    /** How long after the main UI shows before the launch counts as survived. */
    const val SURVIVAL_DELAY_MS = 30_000L

    private const val FILE_NAME = "startup.breadcrumbs"
    private const val PREV_FILE_NAME = "startup.breadcrumbs.prev"
    private const val COUNTER_FILE_NAME = "startup.failures"

    private val log = LoggerFactory.getLogger(StartupBreadcrumbs::class.java)

    @Volatile
    private var file: File? = null
    @Volatile
    private var prevFile: File? = null
    @Volatile
    private var safeModeAdvised = false
    @Volatile
    private var initTimeMs = 0L
    private val lock = Any()

    /**
     * Must be called FIRST in `Application.onCreate` (right after
     * `CrashReporter.init`), before anything that can crash. Decides the
     * safe-mode verdict for this launch from the previous launch's trail.
     */
    @JvmStatic
    fun init(filesDir: File) {
        try {
            synchronized(lock) {
                initTimeMs = System.currentTimeMillis()
                val f = File(filesDir, FILE_NAME)
                val prev = File(filesDir, PREV_FILE_NAME)
                val counterFile = File(filesDir, COUNTER_FILE_NAME)
                file = f
                prevFile = prev

                val previousContent = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
                val previous = classifyPrevious(previousContent)
                if (previous == PreviousLaunch.INCOMPLETE) {
                    // Preserve the DYING launch's trail for the support report.
                    // A safe-mode trail is deliberately NOT preserved — it would
                    // overwrite the crashed launch's evidence with a boring one.
                    runCatching { prev.delete(); f.renameTo(prev) }
                }

                val storedCounter = runCatching { counterFile.readText().trim().toInt() }.getOrDefault(0)
                val (counterToStore, advise) = nextCounterAndSafeMode(previous, storedCounter)
                safeModeAdvised = advise
                runCatching { counterFile.writeText(counterToStore.toString()) }

                runCatching {
                    f.writeText(
                        "# launch ${java.util.Date(initTimeMs)} " +
                            "(previous=$previous failures=$counterToStore safeMode=$advise)\n"
                    )
                }
            }
            mark(STAGE_APP_ONCREATE, "APP_ONCREATE")
        } catch (t: Throwable) {
            // Never let diagnostics take the launch down.
            safeModeAdvised = false
        }
    }

    /**
     * Whether THIS launch should skip the wallet load and engine starts so
     * the app is guaranteed to open and offer the crash report. Decided
     * once, in [init].
     */
    @JvmStatic
    fun isSafeModeAdvised(): Boolean = safeModeAdvised

    /** Append one stage marker: `<n> <name> +<elapsed>ms [detail]`. */
    @JvmStatic
    @JvmOverloads
    fun mark(stage: Int, name: String, detail: String? = null) {
        val line = buildString {
            append(stage).append(' ').append(name)
            append(" +").append(System.currentTimeMillis() - initTimeMs).append("ms")
            if (!detail.isNullOrEmpty()) append(' ').append(detail)
        }
        try {
            log.info("STARTUP breadcrumb: {}", line)
        } catch (t: Throwable) {
            // logging not up yet — the file is the channel
        }
        try {
            synchronized(lock) {
                file?.appendText(line + "\n")
            }
        } catch (t: Throwable) {
            // best-effort only
        }
    }

    /**
     * The launch survived: the main UI has been up for [SURVIVAL_DELAY_MS].
     * Resets the consecutive-failure counter (via the next [init] seeing the
     * complete trail) by writing the [STAGE_MAIN_UI_SHOWN] marker.
     */
    @JvmStatic
    fun markLaunchSurvived() {
        mark(STAGE_MAIN_UI_SHOWN, "MAIN_UI_SHOWN")
    }

    /**
     * The current + previous launch trails, for inlining into the support
     * report. Small (a dozen short lines per launch). Never throws.
     */
    @JvmStatic
    fun reportText(): String = try {
        synchronized(lock) {
            buildString {
                append("--- current launch ---\n")
                append(file?.takeIf { it.exists() }?.readText() ?: "(no breadcrumb file)\n")
                val prev = prevFile?.takeIf { it.exists() }
                if (prev != null) {
                    append("\n--- previous INCOMPLETE launch (died before the main UI) ---\n")
                    append(prev.readText())
                }
            }
        }
    } catch (t: Throwable) {
        "(breadcrumbs unavailable: $t)"
    }

    // ── Pure decision logic (unit-tested) ─────────────────────────────

    /** What the previous launch's trail says happened to it. */
    internal enum class PreviousLaunch {
        /** No trail at all — first run (or the file was cleared). */
        NONE,
        /** The survival marker is present — a full healthy launch. */
        COMPLETE,
        /**
         * A SAFE-MODE launch (header says so). It never shows the main UI by
         * design, so it must not count as a death — but it also proves
         * nothing about the wallet load, so it must not reset the strike
         * counter either. Neutral.
         */
        SAFE_MODE,
        /** Died before the survival marker — a launch death (crash/kill/OOM/native). */
        INCOMPLETE
    }

    @JvmStatic
    internal fun classifyPrevious(content: String?): PreviousLaunch = when {
        content == null -> PreviousLaunch.NONE
        isLaunchComplete(content) -> PreviousLaunch.COMPLETE
        content.lineSequence().firstOrNull()?.contains("safeMode=true") == true -> PreviousLaunch.SAFE_MODE
        else -> PreviousLaunch.INCOMPLETE
    }

    /** Whether a launch trail contains the survival marker. */
    @JvmStatic
    internal fun isLaunchComplete(content: String): Boolean =
        content.lineSequence().any { line ->
            parseStage(line) == STAGE_MAIN_UI_SHOWN
        }

    /** The stage number of a breadcrumb line, or null for headers/garbage. */
    @JvmStatic
    internal fun parseStage(line: String): Int? {
        val token = line.trim().substringBefore(' ')
        if (token.isEmpty() || !token.all { it.isDigit() }) return null
        return token.toIntOrNull()
    }

    /** The LAST stage a trail reached, or null when it has no marker lines. */
    @JvmStatic
    internal fun lastStage(content: String): Int? =
        content.lineSequence().mapNotNull { parseStage(it) }.lastOrNull()

    /**
     * Counter/safe-mode transition:
     * - previous launch COMPLETE (or first run) → counter resets, no safe mode;
     * - previous launch SAFE_MODE → neutral: keep the counter, run NORMALLY —
     *   this is what makes the loop alternate crash → SAFE (report offered) →
     *   normal retry → … instead of locking into safe mode forever;
     * - previous launch INCOMPLETE → counter increments; at
     *   [SAFE_MODE_THRESHOLD] safe mode is advised and the stored counter
     *   decays by one (the retry after the safe-mode launch needs only ONE
     *   more death to re-trip, not two).
     */
    @JvmStatic
    internal fun nextCounterAndSafeMode(previous: PreviousLaunch, storedCounter: Int): Pair<Int, Boolean> =
        when (previous) {
            PreviousLaunch.NONE, PreviousLaunch.COMPLETE -> 0 to false
            PreviousLaunch.SAFE_MODE -> storedCounter to false
            PreviousLaunch.INCOMPLETE -> {
                val incremented = storedCounter + 1
                if (incremented >= SAFE_MODE_THRESHOLD) {
                    (incremented - 1) to true
                } else {
                    incremented to false
                }
            }
        }
}
