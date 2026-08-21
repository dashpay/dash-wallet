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

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces event bursts into rate-limited executions of [action]:
 * at most one run per [intervalMs], LATEST STATE WINS.
 *
 * Built for the peer-connectivity notification flood: a flapping network
 * delivered 233 peer connect/disconnect events in one session, and each one
 * posted its own NotificationManager binder call — 233 events do not need 233
 * status-bar updates. Callers keep their latest state somewhere the action can
 * read (e.g. an AtomicInteger peer count) and call [schedule] on every event;
 * the action runs once per interval with whatever the state is by then.
 *
 * Threading: [schedule] may be called from any thread. The action runs on
 * whatever thread services [postDelayed] (a Handler on its own thread, in
 * production). An event arriving WHILE the action runs re-schedules — nothing
 * is ever dropped, only coalesced.
 */
class ThrottledRunner(
    private val intervalMs: Long,
    /** Schedule a runnable after a delay — `handler::postDelayed` in production. */
    private val postDelayed: (Runnable, Long) -> Unit,
    /** Monotonic clock in ms — `SystemClock::uptimeMillis` in production. */
    private val clock: () -> Long,
    private val action: Runnable
) {
    private val scheduled = AtomicBoolean(false)

    @Volatile
    private var lastRunAtMs = Long.MIN_VALUE / 2 // "long ago": the first run is immediate

    /** Request a run. No-op when one is already pending — latest state wins. */
    fun schedule() {
        if (scheduled.compareAndSet(false, true)) {
            postDelayed(runner, nextDelayMs(lastRunAtMs, clock(), intervalMs))
        }
    }

    private val runner = Runnable {
        lastRunAtMs = clock()
        // Clear the gate BEFORE running so an event arriving mid-action
        // re-schedules (for the following interval) instead of being lost.
        scheduled.set(false)
        action.run()
    }

    companion object {
        /**
         * How long to wait before the next allowed run: immediately when the
         * last run is at least [intervalMs] old, otherwise the remainder of
         * the interval. Pure, for tests.
         */
        @JvmStatic
        fun nextDelayMs(lastRunAtMs: Long, nowMs: Long, intervalMs: Long): Long =
            (intervalMs - (nowMs - lastRunAtMs)).coerceIn(0L, intervalMs)
    }
}
