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
 *     when that launch died BEFORE the launch-complete milestone (so the
 *     evidence of the LAST DEATH always survives exactly one more launch, and
 *     rides along in the support report — see [reportText]);
 *  2. maintains a consecutive-failed-launch counter, and advises SAFE MODE
 *     ([isSafeModeAdvised]) after [SAFE_MODE_THRESHOLD] consecutive launches
 *     died before that milestone. A safe-mode launch skips the wallet load and
 *     every engine start so the app is guaranteed to OPEN and offer the crash
 *     report (`OnboardingActivity` shows the support dialog).
 *
 * ## What counts as a launch FAILURE (and what emphatically does not)
 *
 * The milestone is [STAGE_LAUNCH_COMPLETE] — `Application.onCreate` returning.
 * Once a launch reaches it, the launch SUCCEEDED, full stop, and the counter
 * is reset ON THE SPOT ([markLaunchComplete]) rather than inferred later from
 * a survival timer.
 *
 * This is deliberate and load-bearing. The earlier design required the DELAYED
 * survival marker (30 s after the main UI) before it would call a launch
 * complete, which made every ordinary process death look like a launch death:
 * a QA device ran the app to completion (trail through
 * `23 CUTOVER_SERVICES_STARTED`), Android's lowmemorykiller reclaimed the
 * BACKGROUNDED process hours later, and that reclaim was counted as a launch
 * failure — two of them latched safe mode and the user was handed the
 * crash-report screen with no access to their wallet. A process death AFTER
 * the milestone (LMK reclaim, swipe-away, system kill, battery, reboot) is not
 * a launch failure and must never be counted as one.
 *
 * The UI markers ([STAGE_MAIN_UI_SHOWN], written the moment the UI is on
 * screen, and [STAGE_LAUNCH_SURVIVED], written [SURVIVAL_DELAY_MS] later) are
 * kept as extra evidence in the report and as a belt-and-braces counter reset,
 * but they are no longer what makes a launch "complete".
 *
 * ## Never trapping the user
 *
 * Safe mode is self-clearing at three independent levels:
 *  - a safe-mode launch is NEUTRAL for the next launch, which always retries a
 *    full normal start (crash → SAFE + report → normal retry → …);
 *  - any launch that reaches the milestone resets the counter to zero;
 *  - a HARD CAP ([MAX_SAFE_MODE_RUNS]) limits how many times safe mode may
 *    engage without a completed launch in between; past the cap the app tries
 *    a normal load regardless of the strike count.
 * On top of that, `WalletApplication.retryWalletLoadAfterSafeMode()` lets a
 * safe-mode launch escape IN PROCESS (see [clearSafeModeLatch]) — without it,
 * re-opening the app while the safe-mode process was still alive re-showed the
 * degraded screen forever, because `Application.onCreate` (and therefore this
 * verdict) does not re-run on a warm start.
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

    /**
     * THE LAUNCH-COMPLETE MILESTONE: `Application.onCreate` returned. Reaching
     * it means the launch succeeded — everything that can crash-loop the app
     * (the wallet load above all) is behind us. Written by [markLaunchComplete],
     * which also clears the failure counter on the spot.
     */
    const val STAGE_LAUNCH_COMPLETE = 11

    /** Historical alias — the milestone marker used to be named for onCreate. */
    const val STAGE_ONCREATE_COMPLETE = STAGE_LAUNCH_COMPLETE

    /** The UI is on screen. Written IMMEDIATELY (no delay). */
    const val STAGE_MAIN_UI_SHOWN = 12

    /** The UI has been up for [SURVIVAL_DELAY_MS] — the strongest health signal. */
    const val STAGE_LAUNCH_SURVIVED = 13

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

    /**
     * TIME guard: the wallet load blew its budget (see [WalletLoadBudget]).
     * Written from a watchdog thread WHILE the load is still running, so it
     * survives a kill that leaves no Java stack.
     */
    const val STAGE_WALLET_LOAD_OVERBUDGET = 97

    /** A safe-mode launch is retrying the normal wallet load IN PROCESS. */
    const val STAGE_SAFE_MODE_RETRY = 98

    /** …and that retry SUCCEEDED: safe mode was a false alarm, the latch is cleared. */
    const val STAGE_SAFE_MODE_RETRY_OK = 99

    /** Consecutive pre-milestone deaths before a safe-mode launch is advised. */
    const val SAFE_MODE_THRESHOLD = 2

    /**
     * HARD CAP: how many times safe mode may engage without a completed launch
     * in between. Past the cap the app attempts a normal load regardless of the
     * strike count and the run counter restarts — so no history, however
     * pathological, can leave a user stuck on the degraded screen.
     */
    const val MAX_SAFE_MODE_RUNS = 2

    /** How long after the UI shows before [STAGE_LAUNCH_SURVIVED] is written. */
    const val SURVIVAL_DELAY_MS = 30_000L

    private const val FILE_NAME = "startup.breadcrumbs"
    private const val PREV_FILE_NAME = "startup.breadcrumbs.prev"
    private const val COUNTER_FILE_NAME = "startup.failures"
    private const val SAFE_MODE_RUNS_FILE_NAME = "startup.safemoderuns"

    private val log = LoggerFactory.getLogger(StartupBreadcrumbs::class.java)

    @Volatile
    private var file: File? = null
    @Volatile
    private var prevFile: File? = null
    @Volatile
    private var counterFile: File? = null
    @Volatile
    private var safeModeRunsFile: File? = null
    @Volatile
    private var safeModeAdvised = false
    @Volatile
    private var initTimeMs = 0L
    /** How the PREVIOUS launch was classified — for the support report. */
    @Volatile
    private var previousVerdict: PreviousLaunch = PreviousLaunch.NONE
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
                val runsFile = File(filesDir, SAFE_MODE_RUNS_FILE_NAME)
                file = f
                prevFile = prev
                this.counterFile = counterFile
                this.safeModeRunsFile = runsFile

                val previousContent = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
                val previous = classifyPrevious(previousContent)
                previousVerdict = previous
                val previousLastStage = previousContent?.let { lastStage(it) }
                if (previous == PreviousLaunch.INCOMPLETE_PRE_MILESTONE) {
                    // Preserve the DYING launch's trail for the support report.
                    // A safe-mode trail is deliberately NOT preserved — it would
                    // overwrite the crashed launch's evidence with a boring one.
                    runCatching { prev.delete(); f.renameTo(prev) }
                }

                val storedCounter = readCount(counterFile)
                val storedSafeModeRuns = readCount(runsFile)
                val next = nextLaunchState(previous, storedCounter, storedSafeModeRuns)
                safeModeAdvised = next.safeMode
                runCatching { counterFile.writeText(next.failures.toString()) }
                runCatching { runsFile.writeText(next.safeModeRuns.toString()) }

                runCatching {
                    f.writeText(
                        "# launch ${java.util.Date(initTimeMs)} " +
                            "(previous=$previous prevLastStage=${previousLastStage ?: "none"} " +
                            "failures=${next.failures} safeModeRuns=${next.safeModeRuns} " +
                            "safeMode=${next.safeMode})\n"
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
     * THE LAUNCH-COMPLETE MILESTONE — call at the end of
     * `Application.onCreate`. Writes [STAGE_LAUNCH_COMPLETE] and, unless this
     * launch is itself a safe-mode launch (which proves nothing about the
     * wallet load it skipped), clears the consecutive-failure state RIGHT HERE.
     *
     * Persisting the verdict at the milestone — rather than inferring it later
     * from a survival timer — is what keeps a process death hours later (LMK
     * reclaim, swipe-away, reboot) from being misread as a launch failure.
     */
    @JvmStatic
    fun markLaunchComplete() {
        mark(STAGE_LAUNCH_COMPLETE, "ONCREATE_COMPLETE")
        if (!safeModeAdvised) {
            clearLaunchFailureState()
        }
    }

    /** The UI is on screen. Written immediately — no delay, no inference. */
    @JvmStatic
    @JvmOverloads
    fun markMainUiShown(name: String = "MAIN_UI_SHOWN") {
        mark(STAGE_MAIN_UI_SHOWN, name)
    }

    /**
     * ESCALATE the crash-loop breaker: if THIS launch dies before the
     * launch-complete milestone, the very NEXT launch runs in safe mode —
     * instead of needing [SAFE_MODE_THRESHOLD] deaths first.
     *
     * Used when a launch is observably in trouble but has not died yet (the
     * wallet load blowing its time budget — see [WalletLoadBudget]). It only
     * pre-loads the strike counter to [SAFE_MODE_THRESHOLD] - 1; the next
     * [init] still requires this launch to have actually died before the
     * milestone to advise safe mode. A launch that goes over budget and then
     * COMPLETES clears the counter in [markLaunchComplete] — so this can never
     * cause a spurious safe-mode launch.
     *
     * Never throws.
     */
    @JvmStatic
    fun armSafeModeOnNextDeath() {
        try {
            synchronized(lock) {
                val f = counterFile ?: return
                val stored = runCatching { f.readText().trim().toInt() }.getOrDefault(0)
                val armed = maxOf(stored, SAFE_MODE_THRESHOLD - 1)
                if (armed != stored) {
                    runCatching { f.writeText(armed.toString()) }
                }
            }
        } catch (t: Throwable) {
            // best-effort only
        }
    }

    /**
     * The launch has been on screen for [SURVIVAL_DELAY_MS]. Belt and braces
     * on top of [markLaunchComplete]: the strongest possible health signal,
     * recorded in the trail and mirrored into the counter files so the latch is
     * clear even if the trail file itself is later lost.
     */
    @JvmStatic
    fun markLaunchSurvived() {
        mark(STAGE_LAUNCH_SURVIVED, "LAUNCH_SURVIVED")
        clearLaunchFailureState()
    }

    /**
     * SAFE-MODE ESCAPE: this safe-mode launch retried the wallet load and it
     * SUCCEEDED, so the strikes that engaged safe mode were a false alarm.
     * Clears the latch for this process AND on disk, so no later launch engages
     * safe mode off that history. Never throws.
     */
    @JvmStatic
    fun clearSafeModeLatch() {
        safeModeAdvised = false
        clearLaunchFailureState()
        mark(STAGE_SAFE_MODE_RETRY_OK, "SAFE_MODE_RETRY_OK")
    }

    /** Zero both persisted counters. Never throws. */
    private fun clearLaunchFailureState() {
        try {
            synchronized(lock) {
                counterFile?.let { runCatching { it.writeText("0") } }
                safeModeRunsFile?.let { runCatching { it.writeText("0") } }
            }
        } catch (t: Throwable) {
            // best-effort only
        }
    }

    private fun readCount(f: File): Int = runCatching { f.readText().trim().toInt() }.getOrDefault(0)

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
                    append("\n--- previous INCOMPLETE launch ($previousVerdict — ")
                    append("died before the launch-complete milestone) ---\n")
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
        /** The launch-complete milestone is present — a launch that SUCCEEDED. */
        COMPLETE,
        /**
         * A SAFE-MODE launch (header says so). It skips the wallet load by
         * design, so it must not count as a death — but it also proves nothing
         * about the wallet load, so it must not reset the strike counter
         * either. Neutral: the next launch always retries a NORMAL start.
         */
        SAFE_MODE,
        /**
         * Died BEFORE the launch-complete milestone: a genuine launch failure
         * (crash / OOM-kill / native death / ANR-kill during startup). This is
         * the ONLY classification that counts as a strike.
         */
        INCOMPLETE_PRE_MILESTONE,
        /**
         * A trail exists but carries no stage markers at all — the file was
         * truncated/clobbered, or the write channel failed. We cannot prove a
         * pre-milestone death, so this is NEUTRAL rather than a strike: a
         * filesystem hiccup must never be able to latch safe mode.
         */
        INCOMPLETE_UNKNOWN
    }

    @JvmStatic
    internal fun classifyPrevious(content: String?): PreviousLaunch = when {
        content == null || content.isBlank() -> PreviousLaunch.NONE
        // Header check FIRST: a safe-mode launch is neutral whatever it reached
        // — unless its in-process retry proved the wallet load actually works.
        isSafeModeTrail(content) ->
            if (hasStage(content, STAGE_SAFE_MODE_RETRY_OK)) PreviousLaunch.COMPLETE else PreviousLaunch.SAFE_MODE
        isLaunchComplete(content) -> PreviousLaunch.COMPLETE
        lastStage(content) == null -> PreviousLaunch.INCOMPLETE_UNKNOWN
        else -> PreviousLaunch.INCOMPLETE_PRE_MILESTONE
    }

    private fun isSafeModeTrail(content: String): Boolean =
        content.lineSequence().firstOrNull()?.contains("safeMode=true") == true

    private fun hasStage(content: String, stage: Int): Boolean =
        content.lineSequence().any { parseStage(it) == stage }

    /**
     * Whether a trail proves the launch REACHED COMPLETION.
     *
     * The milestone marker is the primary evidence; the UI markers and the
     * async engine lane are accepted as independent corroboration, so a trail
     * that lost the milestone line but plainly shows a running app is never
     * misread as a death.
     */
    @JvmStatic
    internal fun isLaunchComplete(content: String): Boolean =
        content.lineSequence().mapNotNull { parseStage(it) }.any { isLaunchCompleteStage(it) }

    /** Whether reaching this stage proves the launch completed. */
    @JvmStatic
    internal fun isLaunchCompleteStage(stage: Int): Boolean =
        stage == STAGE_LAUNCH_COMPLETE ||
            stage == STAGE_MAIN_UI_SHOWN ||
            stage == STAGE_LAUNCH_SURVIVED ||
            stage in STAGE_SDK_BIND_KICKED..STAGE_CUTOVER_SERVICES_STARTED

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

    /** The persisted crash-loop-breaker state a launch starts with. */
    internal data class LaunchState(
        /** Consecutive pre-milestone deaths to carry forward. */
        val failures: Int,
        /** Safe-mode launches since the last COMPLETE launch (the hard cap). */
        val safeModeRuns: Int,
        /** Whether THIS launch runs in safe mode. */
        val safeMode: Boolean
    )

    /**
     * Counter/safe-mode transition:
     * - previous launch COMPLETE (or first run) → everything resets, no safe mode;
     * - previous launch SAFE_MODE → neutral: keep the counters, run NORMALLY —
     *   this is what makes the loop alternate crash → SAFE (report offered) →
     *   normal retry → … instead of locking into safe mode;
     * - previous launch INCOMPLETE_UNKNOWN → neutral: an unreadable trail is
     *   not evidence of a death, so it must not push a user toward safe mode;
     * - previous launch INCOMPLETE_PRE_MILESTONE → strike; at
     *   [SAFE_MODE_THRESHOLD] safe mode is advised and the stored counter
     *   decays by one (the retry after the safe-mode launch needs only ONE
     *   more death to re-trip, not two) — UNLESS the [MAX_SAFE_MODE_RUNS] hard
     *   cap is already reached, in which case the app tries a normal load
     *   regardless and the run counter restarts.
     */
    @JvmStatic
    internal fun nextLaunchState(
        previous: PreviousLaunch,
        storedCounter: Int,
        storedSafeModeRuns: Int
    ): LaunchState = when (previous) {
        PreviousLaunch.NONE, PreviousLaunch.COMPLETE -> LaunchState(0, 0, false)
        PreviousLaunch.SAFE_MODE, PreviousLaunch.INCOMPLETE_UNKNOWN ->
            LaunchState(storedCounter, storedSafeModeRuns, false)
        PreviousLaunch.INCOMPLETE_PRE_MILESTONE -> {
            val incremented = storedCounter + 1
            when {
                incremented < SAFE_MODE_THRESHOLD -> LaunchState(incremented, storedSafeModeRuns, false)
                // HARD CAP: too many safe-mode launches with no completed launch
                // in between. Try a NORMAL load — a user must never be able to
                // reach a state where repeated opens only ever show the
                // degraded screen. The run counter restarts, so the breaker can
                // arm again if the app keeps dying.
                storedSafeModeRuns >= MAX_SAFE_MODE_RUNS -> LaunchState(incremented - 1, 0, false)
                else -> LaunchState(incremented - 1, storedSafeModeRuns + 1, true)
            }
        }
    }
}
