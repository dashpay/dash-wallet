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

package de.schildbach.wallet.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** The slice of `PowerManager.WakeLock` the replay hold uses — a seam for host tests. */
interface ReplayWakeLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * The SDK replay's own acquisition of the blockchain service's partial wake
 * lock (no timeout).
 *
 * On the SDK path no peergroup start acquires the lock, so a replay that the
 * idle rule keeps alive could still be dozed. The lock is held for the span
 * the service is kept alive for, minus the stretches the replay cannot
 * progress ([replayHoldsWakeLock]: offline, or wedged with the filter-stall
 * watchdog's restarts spent).
 *
 * Who decides:
 * - the minute tick acquires and releases ([hold]);
 * - the moments a stuck replay can move again re-acquire at once
 *   ([reacquireIfDue]): the network impediment clearing, and the filter wedge
 *   clearing ([observeWedge]). ACTION_TIME_TICK is not delivered while the CPU
 *   sleeps, so waiting for the tick would leave a screen-off replay asleep
 *   until something unrelated woke the device.
 *
 * Tracked apart from `isHeld` because the lock is reference-counted and shared
 * with the dashj path ([releaseForTeardown] releases only this owner's count).
 * The tick runs on the main thread and the re-acquires on IO, so the held flag
 * and the lock change together under one monitor.
 *
 * @param lock the service's wake lock; null before onCreate assigns it.
 * @param sdkPath whether the SDK owns L1 (the dashj path manages its own hold).
 * @param replayDue the replay half of the hold decision, read now.
 */
internal class ReplayWakeLock(
    private val lock: () -> ReplayWakeLockHandle?,
    private val sdkPath: () -> Boolean,
    private val replayDue: () -> Boolean
) {
    private val log = LoggerFactory.getLogger(ReplayWakeLock::class.java)
    private val guard = Any()

    @Volatile
    var held: Boolean = false
        private set

    /**
     * Set first thing in onDestroy: from then on the lock is only ever released.
     * A network-callback coroutine launched just before the callback was
     * unregistered can still run, and its re-acquire landing after the
     * teardown's release would hold a no-timeout lock until the process died.
     */
    @Volatile
    private var retired = false

    /** The tick's decision: [hold] true while the replay (or an engine start / durable save) needs the CPU. */
    fun hold(hold: Boolean) {
        if (!sdkPath()) return
        val lock = lock() ?: return
        synchronized(guard) {
            try {
                if (hold && !held && !retired) {
                    log.info("replay or engine start in progress on the SDK path — acquiring the wake lock")
                    lock.acquire()
                    held = true
                } else if (!hold && held && !replayDue()) {
                    // replayDue() re-reads the inputs under the guard: the tick
                    // decided from a reading an event may have overtaken (the
                    // network back, the wedge cleared, the lock re-acquired) since.
                    log.info("replay / engine start complete or unable to progress — releasing the wake lock")
                    held = false
                    if (lock.isHeld) lock.release()
                }
            } catch (t: Throwable) {
                log.warn("wake lock hold/release for the replay failed", t)
            }
        }
    }

    /** Acquire-only: take the lock back if the replay is due it now. Releasing stays the tick's decision. */
    fun reacquireIfDue() {
        if (!sdkPath()) return
        if (replayDue()) hold(true)
    }

    /**
     * Re-check the moment the filter wedge clears ([wedged] turns false after
     * the first value): the cursor moved, wallet events resumed, the scan
     * caught up, or the engine stopped.
     */
    fun observeWedge(scope: CoroutineScope, wedged: Flow<Boolean>): Job = scope.launch {
        wedged.distinctUntilChanged()
            .drop(1) // the current value is not a transition
            .filter { !it }
            .collect {
                log.info("the filter wedge cleared — re-checking the replay wake lock")
                reacquireIfDue()
            }
    }

    /** onDestroy, first thing: no acquisition after this. */
    fun retire() {
        retired = true
    }

    /** onDestroy, cleanup: release this owner's count, if any. */
    fun releaseForTeardown() {
        val lock = lock() ?: return
        synchronized(guard) {
            if (held) {
                held = false
                if (lock.isHeld) {
                    log.debug("releasing the replay's wake-lock acquisition")
                    lock.release()
                }
            }
        }
    }
}
