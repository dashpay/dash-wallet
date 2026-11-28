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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.service

/** Counts scheduled destroys and times the continuous interval in which cleanup is pending. */
internal class PendingServiceCleanup {
    private var count = 0
    private var startedAtMs = 0L

    @Synchronized
    fun schedule(nowMs: Long) {
        if (count == 0) startedAtMs = nowMs
        count++
    }

    @Synchronized
    fun finish() {
        check(count > 0) { "No scheduled cleanup to finish" }
        if (--count == 0) startedAtMs = 0L
    }

    @Synchronized
    fun isPending(): Boolean = count > 0

    @Synchronized
    fun elapsedMs(nowMs: Long): Long = if (count == 0) 0L else nowMs - startedAtMs
}
