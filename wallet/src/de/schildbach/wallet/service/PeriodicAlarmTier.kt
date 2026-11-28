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

import android.app.AlarmManager
import de.schildbach.wallet.Constants
import java.util.concurrent.TimeUnit

/**
 * The periodic blockchain-sync alarm's cadence, by how recently the wallet was
 * used: every 15 minutes within [Constants.LAST_USAGE_THRESHOLD_JUST_MS] of use,
 * twice a day within [Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS], daily after.
 *
 * The alarm is a `setInexactRepeating` alarm, so its cadence only changes when
 * something re-arms it. It used to be re-armed only when the service was
 * CREATED; a fire delivered to a service that was already running changed
 * nothing, so a 15-minute arm kept firing every 15 minutes indefinitely after
 * the user stopped using the app (471 starts in five days on a Pixel 8a, all
 * `periodic-15min`, against 15 arms, each a cold start of a 61 MB wallet
 * whenever the process had been reaped). [needsRearm] lets every periodic fire
 * step the cadence down as soon as the usage tier has moved.
 */
object PeriodicAlarmTier {
    private val PERIODIC_REASON = Regex("^periodic-(\\d+)min$")

    /** The alarm interval for a wallet last used [lastUsedAgoMs] ago. */
    @JvmStatic
    fun intervalMs(lastUsedAgoMs: Long): Long = when {
        lastUsedAgoMs < Constants.LAST_USAGE_THRESHOLD_JUST_MS -> AlarmManager.INTERVAL_FIFTEEN_MINUTES
        lastUsedAgoMs < Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS -> AlarmManager.INTERVAL_HALF_DAY
        else -> AlarmManager.INTERVAL_DAY
    }

    /** The start-reason extra the periodic alarm carries for [intervalMs]. */
    @JvmStatic
    fun startReason(intervalMs: Long): String = "periodic-${TimeUnit.MILLISECONDS.toMinutes(intervalMs)}min"

    /**
     * Whether a start delivered by the periodic alarm with [startReason] was
     * armed for a different tier than [lastUsedAgoMs] calls for now. False for
     * any other start reason (the replay restart alarm, a manual start).
     */
    @JvmStatic
    fun needsRearm(startReason: String?, lastUsedAgoMs: Long): Boolean {
        val firedMinutes = startReason?.let { PERIODIC_REASON.matchEntire(it) }
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: return false
        return firedMinutes != TimeUnit.MILLISECONDS.toMinutes(intervalMs(lastUsedAgoMs))
    }
}
