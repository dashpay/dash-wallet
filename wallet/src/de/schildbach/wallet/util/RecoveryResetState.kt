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

import androidx.annotation.VisibleForTesting
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

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
 * write fails the recovery save). While it is present, every blockchain
 * service start performs the reset in its onCreate before opening any store,
 * and only that reset removes it, once every part succeeded (or after
 * [MAX_FAILED_ATTEMPTS]). A bare file beside the wallet file, like
 * [WalletWipeState].
 *
 * FAIL CLOSED. Only a marker CONFIRMED absent means no reset is owed: an
 * inspection that cannot tell ([Marker.UNKNOWN]) counts as pending, so
 * [isPending] keeps the blockchain service from opening the stale stores,
 * and [complete] does not report a reset done unless the marker is
 * confirmed gone. `File.exists()` cannot be used for this: it also answers
 * false when the file system cannot establish whether the file is there.
 */
object RecoveryResetState {
    private val log = LoggerFactory.getLogger(RecoveryResetState::class.java)

    const val MARKER_FILE_NAME = "recovery-reset.pending"

    /**
     * While the marker is pending the blockchain service resets instead of
     * syncing, so a database clear that fails every time would leave the
     * wallet never syncing. After this many failed resets a failing database
     * clear is waived (those stores are re-syncable) and the wallet syncs on
     * whatever the partial clear left. Nothing else is ever waived: an SPV
     * store that cannot be deleted, or an SDK rescan that cannot be owed,
     * keeps the reset owed.
     */
    const val MAX_FAILED_ATTEMPTS = 3

    /** What inspecting the marker established. */
    enum class Marker {
        /** Confirmed absent: no reset is owed. */
        ABSENT,

        /** Present: a reset is owed. */
        PENDING,

        /** Neither could be established (I/O or permission failure): treated as [PENDING]. */
        UNKNOWN
    }

    private fun marker(dir: File) = File(dir, MARKER_FILE_NAME)

    /**
     * How the marker is inspected. A seam for tests, which cannot make a real
     * file system fail on demand; production code never replaces it.
     */
    @VisibleForTesting
    internal var inspect: (File) -> Marker = ::inspectOnDisk

    /**
     * NOFOLLOW_LINKS: the marker is a plain file; a dangling link is still
     * an entry at that path, so it is not "absent". `Files.exists` and
     * `Files.notExists` both answer false when the check cannot be made,
     * which is the only way to tell that case from a real absence.
     */
    private fun inspectOnDisk(file: File): Marker {
        val path = file.toPath()
        return when {
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> Marker.PENDING
            Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> Marker.ABSENT
            else -> Marker.UNKNOWN
        }
    }

    /** Never throws: a failed inspection is [Marker.UNKNOWN], logged. */
    fun state(dir: File): Marker {
        val file = marker(dir)
        val result = try {
            inspect(file)
        } catch (t: Throwable) {
            log.warn("could not inspect the recovery-reset marker at {}", file, t)
            return Marker.UNKNOWN
        }
        if (result == Marker.UNKNOWN) {
            log.warn("could not establish whether the recovery-reset marker at {} exists — the reset stays owed", file)
        }
        return result
    }

    /** Records that a reset is owed. Throws, unlike the other methods: the caller must not save without it. */
    @Throws(IOException::class)
    fun arm(dir: File) {
        val file = marker(dir)
        if (!file.exists() && !file.createNewFile()) {
            throw IOException("could not create the recovery-reset marker at $file")
        }
    }

    /**
     * True unless the marker is confirmed absent: a recovered wallet's reset
     * was armed and never recorded as done, or the marker cannot be inspected
     * ([Marker.UNKNOWN], fail closed). Never throws.
     */
    fun isPending(dir: File): Boolean = state(dir) != Marker.ABSENT

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

    /**
     * Call ONLY after the reset ran: while the marker is present every service
     * start resets again. Returns whether the marker is now confirmed absent;
     * false (logged) when it could not be deleted, or its absence afterwards
     * cannot be established, so the caller must not treat the reset as
     * complete. Never throws.
     */
    fun complete(dir: File): Boolean {
        val file = marker(dir)
        try {
            file.delete()
        } catch (t: Throwable) {
            log.warn("could not delete the recovery-reset marker at {}", file, t)
        }
        // Whatever delete() answered, only a confirmed absence completes the reset.
        return when (state(dir)) {
            Marker.ABSENT -> true
            Marker.PENDING -> {
                log.warn("could not delete the recovery-reset marker at {} — the reset stays owed", file)
                false
            }
            Marker.UNKNOWN -> false // logged by state()
        }
    }
}
