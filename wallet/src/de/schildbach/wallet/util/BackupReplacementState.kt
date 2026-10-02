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
 * A marker whose presence cannot be established counts as present: the only
 * cost is a backup rewritten from the loaded wallet, which is harmless.
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

    /** Records that the replacement's backup is owed. Throws: the caller must not save the primary without it. */
    @Throws(IOException::class)
    fun arm(dir: File) {
        val file = marker(dir)
        if (!file.exists() && !file.createNewFile()) {
            throw IOException("could not create the backup-replacement marker at $file")
        }
    }

    /** True unless the marker is confirmed absent. Never throws. */
    fun isPending(dir: File): Boolean = try {
        !confirmAbsent(marker(dir))
    } catch (t: Throwable) {
        log.warn("could not inspect the backup-replacement marker — rewriting the key backup", t)
        true
    }

    /**
     * Call ONLY after the replacement's backup is written, or once the wallet
     * it belongs to is gone. Returns whether the marker is now confirmed
     * absent; false (logged) otherwise, which only means the next startup
     * rewrites the backup again. Never throws.
     */
    fun complete(dir: File): Boolean {
        val file = marker(dir)
        try {
            file.delete()
        } catch (t: Throwable) {
            log.warn("could not delete the backup-replacement marker at {}", file, t)
        }
        val cleared = !isPending(dir)
        if (!cleared) log.warn("the backup-replacement marker at {} remains — the next startup rewrites the backup", file)
        return cleared
    }
}
