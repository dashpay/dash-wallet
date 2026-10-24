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
import kotlinx.coroutines.flow.StateFlow
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
 * Who decides: everyone, the same way. The minute tick ([tick]) and every
 * event that can change the answer ([recheck]: an impediment change, the
 * filter wedge clearing via [observeWedgeClears]) read the decision NOW and
 * acquire or release to match, under one monitor. ACTION_TIME_TICK is not
 * delivered while the CPU sleeps, so a replay that can move again must not
 * wait for the tick to get the lock back.
 *
 * The read and the change happen under the same monitor, and so does each
 * later recheck. A recheck that read "due" just before a concurrent network
 * loss can still acquire. But the loss's own recheck then runs after it, reads
 * "not due", and releases. (An acquire-only re-check used to leave that lock
 * held until the next tick, so the replay held the CPU while offline.)
 *
 * The tick also knows holds the events cannot see (an engine start, a durable
 * save in flight). It records them, and a recheck honours the last recorded
 * value. A stale one only keeps the lock, and a held lock keeps the CPU awake,
 * so the next tick comes and corrects it.
 *
 * Tracked apart from `isHeld` because the lock is reference-counted and shared
 * with the dashj path ([releaseForTeardown] releases only this owner's count).
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

    /** The tick's holds the events cannot read; written and read under [guard]. */
    private var tickHolds = false

    /**
     * The minute tick. [otherHolds]: an engine start or a durable save needs
     * the CPU (the replay's own part is read by [recheck]).
     */
    fun tick(otherHolds: Boolean) {
        synchronized(guard) { tickHolds = otherHolds }
        recheck()
    }

    /** Hold the lock iff the replay (or the tick's last other holds) needs the CPU now. */
    fun recheck() {
        if (!sdkPath()) return
        val lock = lock() ?: return
        synchronized(guard) {
            try {
                val due = tickHolds || replayDue()
                if (due && !held && !retired) {
                    log.info("replay or engine start in progress on the SDK path — acquiring the wake lock")
                    lock.acquire()
                    held = true
                } else if (!due && held) {
                    log.info("replay / engine start complete or unable to progress — releasing the wake lock")
                    held = false
                    if (lock.isHeld) lock.release()
                }
            } catch (t: Throwable) {
                log.warn("wake lock hold/release for the replay failed", t)
            }
        }
    }

    /**
     * Re-check every time the filter wedge clears: the cursor moved, wallet
     * events resumed, the scan caught up, or the engine stopped.
     *
     * [clears] COUNTS the clears rather than mirroring the flag. A collector
     * that is slow to run can miss intermediate values of a StateFlow, and on a
     * Boolean that loses events: clear → re-wedge → clear arrives as the
     * "not wedged" it already saw, while the minute tick released the lock
     * during the re-wedge — and no re-check came. A count it has not seen
     * always means a clear happened, so it re-checks. The baseline is read when
     * this is CALLED, not when the collector first runs, so a clear in between
     * is not taken for the starting value. [recheck] reads the current
     * state, so a clear that was followed by a new wedge acquires nothing.
     */
    fun observeWedgeClears(scope: CoroutineScope, clears: StateFlow<Long>): Job {
        val baseline = clears.value
        return scope.launch {
            clears.filter { it != baseline }
                .collect {
                    log.info("the filter wedge cleared — re-checking the replay wake lock")
                    recheck()
                }
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
