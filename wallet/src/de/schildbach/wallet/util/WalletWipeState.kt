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
import java.security.SecureRandom

/**
 * The PERSISTED "a Reset Wallet is in flight" marker.
 *
 * A wipe destroys, in sequence, the wallet file, the key-backup file, the
 * keystore secrets, every DataStore preference file and every Room database.
 * That sequence is not short: on a live mainnet device the SDK wallet removal
 * alone ran for 116 seconds (22:38:23 -> 22:40:19) and the process then died
 * inside the same wipe. Nothing on disk recorded that a wipe had been started,
 * so a launch landing in that window has no way to tell a half-destroyed
 * wallet from an intact one — it would load whatever survived and present it
 * to the user as their wallet.
 *
 * The marker is written BEFORE the first destructive step and removed only
 * after the last one. That turns an ambiguous half-wipe into a yes/no answer
 * for the next launch: valid marker present -> the wipe did not finish, finish
 * it before showing any wallet UI; marker absent/invalid -> nothing was
 * destroyed by this install's Reset Wallet flow, so do not destroy anything.
 *
 * Deliberately a file in `filesDir` rather than SharedPreferences or a
 * DataStore: the wipe clears both of those, so neither can record its own
 * progress. The marker is bound to a token in `noBackupFilesDir`, so an empty
 * planted marker or a restored `files/` marker from another install cannot
 * silently authorize destroying a healthy wallet on cold launch.
 *
 * Every method is failure-contained and never throws — a diagnostic/recovery
 * channel must not be able to take the app down.
 */
object WalletWipeState {
    private val log = LoggerFactory.getLogger(WalletWipeState::class.java)

    /** Name kept stable: older invalid markers must still be found and ignored. */
    const val MARKER_FILE_NAME = "wallet-wipe.pending"

    private const val TOKEN_FILE_NAME = "wallet-wipe.install-token"
    private const val MARKER_VERSION = "v1"
    private const val TOKEN_BYTES = 32

    private fun marker(filesDir: File) = File(filesDir, MARKER_FILE_NAME)
    private fun tokenFile(noBackupFilesDir: File) = File(noBackupFilesDir, TOKEN_FILE_NAME)

    /**
     * Records that a wipe has started. MUST return before the first
     * destructive step runs.
     *
     * @return true when a valid marker is on disk. False means the wipe is
     *   about to run unrecorded — it still proceeds, but a mid-wipe process
     *   death would not be recoverable, so the caller logs it.
     */
    fun begin(filesDir: File, noBackupFilesDir: File): Boolean = try {
        val file = marker(filesDir)
        val token = installToken(noBackupFilesDir) ?: return false
        file.writeText(markerBody(token), Charsets.UTF_8)
        isPending(filesDir, noBackupFilesDir)
    } catch (t: Throwable) {
        log.warn("could not create the wallet-wipe marker", t)
        false
    }

    /** True when a wipe was started and never recorded as finished. */
    fun isPending(filesDir: File, noBackupFilesDir: File): Boolean {
        return try {
            val file = marker(filesDir)
            if (!file.exists()) {
                return false
            }
            val token = readInstallToken(noBackupFilesDir)
            val valid = token != null && file.readText(Charsets.UTF_8) == markerBody(token)
            if (!valid) {
                log.warn("ignoring invalid wallet-wipe marker at {}", file)
                removeInvalidMarker(file)
            }
            valid
        } catch (t: Throwable) {
            log.warn("could not read the wallet-wipe marker", t)
            false
        }
    }

    /**
     * Records that the wipe ran to the end. Call ONLY after the last
     * destructive step returned: while this marker is present the next launch
     * re-runs the wipe, and re-running it is always safe, whereas clearing it
     * early leaves the half-wiped state this whole mechanism exists to
     * prevent.
     */
    fun complete(filesDir: File) {
        try {
            val file = marker(filesDir)
            if (file.exists() && !file.delete()) {
                log.warn("could not delete the wallet-wipe marker at {} — the next launch will re-run the wipe", file)
            }
        } catch (t: Throwable) {
            log.warn("could not delete the wallet-wipe marker", t)
        }
    }

    private fun markerBody(token: String) = "$MARKER_VERSION\n$token\n"

    private fun installToken(noBackupFilesDir: File): String? {
        readInstallToken(noBackupFilesDir)?.let { return it }
        return try {
            val token = newToken()
            tokenFile(noBackupFilesDir).writeText("$token\n", Charsets.UTF_8)
            token
        } catch (t: Throwable) {
            log.warn("could not create the wallet-wipe install token", t)
            null
        }
    }

    private fun readInstallToken(noBackupFilesDir: File): String? = try {
        tokenFile(noBackupFilesDir)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.length == TOKEN_BYTES * 2 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    } catch (t: Throwable) {
        log.warn("could not read the wallet-wipe install token", t)
        null
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun removeInvalidMarker(file: File) {
        try {
            if (!file.delete()) {
                log.warn("could not delete invalid wallet-wipe marker at {}", file)
            }
        } catch (t: Throwable) {
            log.warn("could not delete invalid wallet-wipe marker", t)
        }
    }
}
