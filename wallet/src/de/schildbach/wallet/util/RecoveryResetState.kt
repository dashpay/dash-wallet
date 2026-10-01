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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.util

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * The PERSISTED "a wallet recovered from the key backup still owes a
 * blockchain reset" marker.
 *
 * The key backup is transaction-stripped with `lastBlockSeenHeight` cleared,
 * so the recovered wallet only shows its funds and history after a reset
 * rescans its keys. Once the recovered primary is saved, the next launch
 * loads it as an ordinary wallet; without this marker a process death
 * between that save and the reset leaves nothing on disk saying the reset
 * is still owed, and the blockchain service's consistency check skips a
 * wallet whose `lastBlockSeenHeight` is -1.
 *
 * The marker is written BEFORE the recovered primary is saved (a failed
 * write fails the recovery save), every launch that finds it re-queues the
 * reset, and only the blockchain service removes it, after the reset's
 * teardown ran. A bare file beside the wallet file, like [WalletWipeState].
 */
object RecoveryResetState {
    private val log = LoggerFactory.getLogger(RecoveryResetState::class.java)

    const val MARKER_FILE_NAME = "recovery-reset.pending"

    /**
     * While the marker is pending the blockchain service resets instead of
     * syncing, so a database clear that fails every time would leave the
     * wallet never syncing. After this many failed resets the marker is
     * dropped and the wallet syncs on whatever the partial clear left.
     */
    const val MAX_FAILED_ATTEMPTS = 3

    private fun marker(dir: File) = File(dir, MARKER_FILE_NAME)

    /** Records that a reset is owed. Throws, unlike the other methods: the caller must not save without it. */
    @Throws(IOException::class)
    fun arm(dir: File) {
        val file = marker(dir)
        if (!file.exists() && !file.createNewFile()) {
            throw IOException("could not create the recovery-reset marker at $file")
        }
    }

    /** True when a recovered wallet's reset was armed and never recorded as done. */
    fun isPending(dir: File): Boolean = try {
        marker(dir).exists()
    } catch (t: Throwable) {
        log.warn("could not read the recovery-reset marker", t)
        false
    }

    /**
     * Records one reset whose database clear failed, as a count inside the
     * marker. Returns the new count; a marker that cannot be read or written
     * counts as exhausted, since its retries cannot be bounded.
     */
    fun recordFailedAttempt(dir: File): Int = try {
        val file = marker(dir)
        val attempts = (file.readText().trim().toIntOrNull() ?: 0) + 1
        file.writeText(attempts.toString())
        attempts
    } catch (t: Throwable) {
        log.warn("could not record a failed recovery reset", t)
        MAX_FAILED_ATTEMPTS
    }

    /** Call ONLY after the reset ran: while the marker is present every launch re-queues it. */
    fun complete(dir: File) {
        try {
            val file = marker(dir)
            if (file.exists() && !file.delete()) {
                log.warn("could not delete the recovery-reset marker at {} — the next launch resets again", file)
            }
        } catch (t: Throwable) {
            log.warn("could not delete the recovery-reset marker", t)
        }
    }
}
