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

/**
 * Lets the blockchain service stop itself only when no start command newer
 * than the ones it has seen is on its way.
 *
 * Field crash (12.0.0-beta 39, Samsung A53, Android 16), all in one second:
 *
 *     06:38:49  idling detected, stopping service           <- stopSelf()
 *     06:38:49  .onStartCommand(Intent { … (has extras) })  <- periodic alarm
 *     06:38:49  start foreground service
 *     06:38:49  .onDestroy()
 *     06:38:49  ForegroundServiceDidNotStartInTimeException
 *
 * The alarm's `startForegroundService()` had already been accepted by the
 * system (foreground promise armed, new start id issued, the command queued
 * on the main looper) when the idle tick, running first, called `stopSelf()`.
 * That is `stopSelf(-1)`, which the system honours whatever starts are
 * pending; tearing down a service whose foreground promise is still open
 * crashes the process at once, and the `startForeground()` that follows
 * cannot help.
 *
 * `stopSelfResult(id)` stops only when `id` is the newest start id the
 * system has issued, so passing the newest one DELIVERED to `onStartCommand`
 * makes the system refuse the stop exactly when such a start is pending. The
 * id counts as delivered only once that start's foreground promotion has run
 * (see [onStart]), so an accepted stop never leaves a promise open.
 * The comparison happens in the system, so the read here may be stale in
 * either direction without harm: a stale id is refused, never wrongly honoured.
 *
 * Before any start was delivered the id is 0, the value the system holds for
 * a service that was never started: a refused stop again means a start is
 * pending.
 *
 * Pure (no Android types), so the decision is host-testable.
 */
class SelfStopGate {
    /**
     * The id to pass to `stopSelfResult`: the newest start delivered AND
     * promoted so far. Written only by `onStartCommand`, which always runs on
     * the main thread with strictly rising ids; read from any thread.
     */
    @Volatile
    var stopId: Int = NO_START_ID
        private set

    /**
     * Why a stop that had to happen ([requestStop] with `mustStop`) is still
     * owed, or null. Set BEFORE the stop reads [stopId], so the newer start
     * that made the system refuse it always sees it: that start publishes its
     * id ([onStart]) and only then, in its command's coroutine, reads this. Cleared once a must-stop is accepted: a
     * bound service outlives an accepted stop, and its later commands must
     * run normally.
     */
    @Volatile
    var owedStopReason: String? = null
        private set

    /**
     * Called synchronously in `onStartCommand`, right AFTER its foreground
     * promotion (`startForeground()`), never before. Publishing the id is
     * what lets a stop with it be accepted; publishing it earlier would let a
     * stop running off the main thread in between be accepted while the
     * start's foreground promise is still open, which is the crash. Until
     * then such a stop uses the previous id and is refused.
     */
    fun onStart(startId: Int) {
        stopId = startId
    }

    /**
     * Attempts the stop through [stopSelfResult] with the newest delivered
     * start id.
     *
     * @param mustStop the stop may not simply lapse when refused: it is
     *   recorded in [owedStopReason], for the newer start's command to stop
     *   again instead of running.
     * @param onRefused called with the id that was used, when the system
     *   refused the stop.
     * @return true when the system accepted the stop.
     */
    /**
     * Records [reason] as an owed stop WITHOUT attempting one yet: for a decision made before the
     * commands that must honour it are released (the stuck-cleanup bail-out releases the init
     * latch, and a queued command can run before the stop itself is attempted). Cleared, like any
     * owed stop, once a must-stop is accepted.
     */
    fun oweStop(reason: String) {
        owedStopReason = reason
    }

    fun requestStop(
        reason: String,
        mustStop: Boolean,
        onRefused: (startId: Int) -> Unit = {},
        stopSelfResult: (Int) -> Boolean
    ): Boolean {
        if (mustStop) {
            owedStopReason = reason
        }
        val startId = stopId
        val stopped = stopSelfResult(startId)
        if (!stopped) {
            onRefused(startId)
        } else if (mustStop) {
            owedStopReason = null
        }
        return stopped
    }

    companion object {
        const val NO_START_ID = 0
    }
}
