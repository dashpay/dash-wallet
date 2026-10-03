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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The PERSISTED "a replacement wallet's key backup is still owed" marker.
 *
 * Wallet setup saves the replacement primary and only then writes its key
 * backup over the previous wallet's. A process death between the two leaves
 * the previous wallet's backup beside the new primary; comparing the two
 * wallets cannot tell that reliably (a replacement can share the HD seed and
 * add imported keys) and costs a second full wallet load at every startup.
 *
 * So the obligation is recorded instead: the marker is written BEFORE the
 * primary is saved (a failed write fails the setup), and removed only after
 * the replacement's backup is written. While it is present, startup
 * maintenance rewrites the backup from the loaded wallet and only then
 * removes it. A bare file beside the wallet file, like [RecoveryResetState].
 *
 * Each arm writes a fresh random GENERATION into the marker, and a writer
 * may clear only the generation it armed or observed ([complete]). Startup
 * maintenance can be repairing wallet A while onboarding installs wallet B
 * and arms again; A's completion must not discharge B's obligation before
 * B's backup is published. Arming and the compare-and-delete both run under
 * the [AtomicFileWriter] lock, which the key backup write also holds, so a
 * completion never interleaves with an arm. (Only file I/O runs under that
 * lock — never the dashj wallet lock — so it cannot deadlock with autosave.)
 *
 * A marker whose presence or content cannot be established counts as
 * present and is never cleared by [complete]: the only cost is a backup
 * rewritten from the loaded wallet at each startup, which is harmless. A
 * legacy (empty) marker is still owed; a writer that observed it empty may
 * clear it only while it is still empty, which no arm ever writes.
 */
object BackupReplacementState {
    private val log = LoggerFactory.getLogger(BackupReplacementState::class.java)

    const val MARKER_FILE_NAME = "backup-replacement.pending"

    private fun marker(dir: File) = File(dir, MARKER_FILE_NAME)

    /** How the marker's absence is confirmed. A seam for tests; production code never replaces it. */
    @VisibleForTesting
    internal var confirmAbsent: (File) -> Boolean = { file ->
        Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * One obligation: the generation an arm wrote, or the marker content a
     * repair observed. [content] is null when the marker could not be read;
     * such a generation never clears the marker.
     */
    class Generation internal constructor(internal val content: String?) {
        override fun toString(): String = "Generation(${content?.ifEmpty { "<legacy>" } ?: "<unreadable>"})"
    }

    /**
     * Records that the replacement's backup is owed, under a fresh
     * generation, and returns it for the matching [complete]. Throws: the
     * caller must not save the primary without it.
     */
    @Throws(IOException::class)
    fun arm(dir: File): Generation {
        val generation = UUID.randomUUID().toString()
        AtomicFileWriter.runExclusive { writeMarker(dir, generation) }
        return Generation(generation)
    }

    // temp -> fsync -> atomic rename, so the marker is never seen half
    // written. The temp's ".tmp" suffix is swept by cleanupFiles(), which
    // takes the same lock, so it never removes a temp that is being written.
    private fun writeMarker(dir: File, generation: String) {
        val file = marker(dir)
        val temp = File(dir, MARKER_FILE_NAME + AtomicFileWriter.TEMP_SUFFIX)
        FileOutputStream(temp).use { out ->
            out.write(generation.toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** True unless the marker is confirmed absent. Never throws. */
    fun isPending(dir: File): Boolean = try {
        !confirmAbsent(marker(dir))
    } catch (t: Throwable) {
        log.warn("could not inspect the backup-replacement marker — rewriting the key backup", t)
        true
    }

    /**
     * The obligation currently on disk, for a repair to pass to [complete]
     * once it has rewritten the backup: null only when the marker is
     * confirmed absent. An unreadable marker yields a generation that never
     * clears it. Never throws.
     */
    fun observe(dir: File): Generation? {
        var observed: Generation? = null
        AtomicFileWriter.runExclusive {
            observed = if (!isPending(dir)) null else Generation(readMarker(dir))
        }
        return observed
    }

    private fun readMarker(dir: File): String? = try {
        marker(dir).readText(Charsets.US_ASCII)
    } catch (t: Throwable) {
        log.warn("could not read the backup-replacement marker — it stays owed", t)
        null
    }

    /**
     * Call ONLY after the backup of the wallet [generation] belongs to is
     * written. Clears the marker only if it still holds [generation]: a
     * newer arm (another replacement installed meanwhile) is left for its
     * own writer. Returns whether the marker is now confirmed absent; false
     * (logged) otherwise, which only means the next startup rewrites the
     * backup again. Never throws.
     */
    fun complete(dir: File, generation: Generation): Boolean {
        var cleared = false
        AtomicFileWriter.runExclusive {
            cleared = completeLocked(dir, generation)
        }
        return cleared
    }

    private fun completeLocked(dir: File, generation: Generation): Boolean {
        if (!isPending(dir)) return true
        val file = marker(dir)
        val expected = generation.content
        if (expected == null) {
            log.warn("the backup-replacement marker at {} was unreadable when observed — it stays owed", file)
            return false
        }
        val current = readMarker(dir) ?: return false
        if (current != expected) {
            log.warn(
                "the backup-replacement marker at {} was re-armed by a newer replacement — left for its writer",
                file
            )
            return false
        }
        deleteMarker(file)
        return confirmCleared(dir)
    }

    /**
     * Unconditional removal, ONLY for when the wallet it belongs to is gone
     * (Reset Wallet). Never throws.
     */
    fun discard(dir: File): Boolean {
        var cleared = false
        AtomicFileWriter.runExclusive {
            deleteMarker(marker(dir))
            cleared = confirmCleared(dir)
        }
        return cleared
    }

    private fun deleteMarker(file: File) {
        try {
            file.delete()
        } catch (t: Throwable) {
            log.warn("could not delete the backup-replacement marker at {}", file, t)
        }
    }

    private fun confirmCleared(dir: File): Boolean {
        val cleared = !isPending(dir)
        if (!cleared) {
            log.warn(
                "the backup-replacement marker at {} remains — the next startup rewrites the backup",
                marker(dir)
            )
        }
        return cleared
    }
}
