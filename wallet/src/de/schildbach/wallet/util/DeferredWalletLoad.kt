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
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/**
 * The wallet load of a BACKGROUND process start, moved off the main thread.
 *
 * ## Why (12000022 crash reports, Pixel 8a, 61.6 MB wallet)
 *
 * `WalletApplication.onCreate` parsed the wallet and ran its consistency check
 * on the main thread: 9-11 s for the parse and about 4 s for the check in a
 * background start. Android gives a background process a start-up deadline for
 * `Application.onCreate` ("bind application") and kills it when it is missed:
 * `[BIND APPLICATION ANR] bg anr … failed to complete startup`, every time, for
 * every alarm, job and broadcast that cold-started the process. No background
 * sync ever ran on that wallet.
 *
 * Once `onCreate` returns, the components have far longer: a background service
 * start has minutes, a background broadcast a minute. So for a background start
 * the parse and the check run on a worker thread and `onCreate` returns
 * immediately; the rest of the initialisation (publishing the wallet,
 * `finalizeInitialization`, the launch-complete milestone) is posted back to
 * the main thread, where it always ran.
 *
 * ## The gate
 *
 * Anything that asks for the wallet while the load is running waits for it
 * ([awaitForCaller]):
 *  - on the MAIN thread, it waits only for the worker's parse and check, then
 *    runs the main-thread completion INLINE. The completion is main-thread work
 *    anyway, so this cannot deadlock, and the caller sees exactly what a
 *    synchronous start would have given it;
 *  - on any other thread, it waits for the completion to finish, bounded by
 *    [offMainTimeoutMs] so no unforeseen lock cycle can turn into a hang.
 *
 * A foreground start (the user opening the app) is left exactly as it was.
 */
class DeferredWalletLoad @JvmOverloads constructor(
    private val isMainThread: BooleanSupplier,
    private val offMainTimeoutMs: Long = OFF_MAIN_TIMEOUT_MS
) {
    private val loaded = CountDownLatch(1)
    private val completed = CountDownLatch(1)
    private val completionClaimed = AtomicBoolean(false)

    @Volatile
    private var completion: Runnable? = null

    @Volatile
    private var active = false

    /** The worker running the load: exempt from the gate, or a gated call inside the load would wait on itself. */
    @Volatile
    private var workerThread: Thread? = null

    /** True from [start] until the main-thread completion has finished. */
    fun isInProgress(): Boolean = active && completed.count > 0L

    /**
     * Start the deferred load. [load] runs on [worker]; when it returns or
     * throws, [completeOnMain] is handed to [mainThread].
     * [load] must handle its own failures; anything it throws is logged and the
     * completion still runs, so no waiter is ever stranded.
     */
    fun start(
        load: Runnable,
        completeOnMain: Runnable,
        mainThread: Executor,
        worker: Executor
    ) {
        completion = completeOnMain
        active = true
        worker.execute(
            Runnable {
                workerThread = Thread.currentThread()
                try {
                    load.run()
                } catch (t: Throwable) {
                    log.error("deferred wallet load threw past its own handling", t)
                } finally {
                    loaded.countDown()
                    mainThread.execute(Runnable { runCompletion() })
                }
            }
        )
    }

    /** Run the main-thread completion exactly once, whoever gets here first. */
    fun runCompletion() {
        if (!completionClaimed.compareAndSet(false, true)) return
        try {
            completion?.run()
        } finally {
            completed.countDown()
        }
    }

    /**
     * Block until the wallet is usable by the calling thread; a no-op when no
     * deferred load is running. See the class docs for the two cases.
     *
     * Off the main thread the wait is bounded by [offMainTimeoutMs] for a
     * READER, so a reader that holds the dashj wallet lock while it asks
     * cannot deadlock against the completion's `autosaveToFile`: it proceeds
     * after the timeout instead. A caller that REPLACES or DESTROYS the wallet
     * passes [untilComplete] = true and waits for the completion however long
     * it takes, uninterruptibly (review, 2026-09-30): a timed-out setWallet
     * could otherwise assign the restored wallet while the worker is still
     * parsing, and the worker would then overwrite and publish the stale one.
     */
    @JvmOverloads
    fun awaitForCaller(untilComplete: Boolean = false) {
        if (!isInProgress()) return
        if (Thread.currentThread() === workerThread) return
        if (isMainThread.asBoolean) {
            awaitUninterruptibly(loaded)
            runCompletion()
        } else if (untilComplete) {
            awaitUninterruptibly(completed)
        } else {
            val finished = try {
                completed.await(offMainTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished) {
                log.warn(
                    "deferred wallet load: {} waited {} ms for the main-thread completion; proceeding",
                    Thread.currentThread().name,
                    offMainTimeoutMs
                )
            }
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DeferredWalletLoad::class.java)

        /** How long an off-main caller waits for the completion before proceeding. */
        const val OFF_MAIN_TIMEOUT_MS = 60_000L

        // ApplicationStartInfo.START_REASON_* (API 35).
        internal const val START_REASON_LAUNCHER = 6
        internal const val START_REASON_LAUNCHER_RECENTS = 7
        internal const val START_REASON_START_ACTIVITY = 11

        /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND / _VISIBLE. */
        private const val IMPORTANCE_FOREGROUND = 100
        private const val IMPORTANCE_VISIBLE = 200

        /**
         * Whether a start reason is one the user is waiting on: the launcher,
         * recents, or an activity start. Everything else (alarm, job,
         * broadcast, service, boot, push, content provider, backup, other)
         * is a background start.
         */
        @JvmStatic
        internal fun isForegroundStartReason(reason: Int): Boolean =
            reason == START_REASON_LAUNCHER ||
                reason == START_REASON_LAUNCHER_RECENTS ||
                reason == START_REASON_START_ACTIVITY

        /**
         * Fallback below API 35: only FOREGROUND (an activity on top) and
         * VISIBLE mean the user is waiting. A foreground SERVICE (125, which is
         * what an alarm's `startForegroundService` gets), perceptible, service
         * and cached are all background starts. 0 or anything unreadable is
         * treated as foreground, which keeps today's synchronous load.
         */
        @JvmStatic
        internal fun isBackgroundImportance(importance: Int): Boolean =
            importance > IMPORTANCE_FOREGROUND && importance != IMPORTANCE_VISIBLE

        /** How the last [isBackgroundStart] decided, for the start-up log. */
        @Volatile
        @JvmStatic
        var lastDecision: String = "not evaluated"
            private set

        /**
         * Whether THIS process was started for background work, not for the
         * user. Any doubt answers false, which keeps today's synchronous load.
         * Never throws.
         *
         * API 35+: the NEWEST start record is this process's own, still in
         * progress. Its `pid` is not usable: the system records 0 there (seen
         * on API 36, 2026-09-29), so matching on `Process.myPid()` never found
         * the record and every start fell back to "foreground". A newest record
         * that is no longer in progress belongs to an earlier process, and the
         * importance fallback decides instead.
         */
        @JvmStatic
        fun isBackgroundStart(context: Context): Boolean = try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) {
                lastDecision = "no ActivityManager: foreground"
                false
            } else {
                val start: ApplicationStartInfo? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        activityManager.getHistoricalProcessStartReasons(1).firstOrNull()
                            ?.takeIf { it.startupState == ApplicationStartInfo.STARTUP_STATE_STARTED }
                    } else {
                        null
                    }
                if (start != null) {
                    val background = !isForegroundStartReason(start.reason)
                    lastDecision = "start reason ${start.reason}: ${if (background) "background" else "foreground"}"
                    background
                } else {
                    val info = ActivityManager.RunningAppProcessInfo()
                    ActivityManager.getMyMemoryState(info)
                    val background = isBackgroundImportance(info.importance)
                    lastDecision = "importance ${info.importance}: ${if (background) "background" else "foreground"}"
                    background
                }
            }
        } catch (t: Throwable) {
            lastDecision = "failed ($t): foreground"
            false
        }
    }
}
