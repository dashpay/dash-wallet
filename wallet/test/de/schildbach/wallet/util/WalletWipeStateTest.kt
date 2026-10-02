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

import de.schildbach.wallet.util.WalletWipeState.Entry
import de.schildbach.wallet.util.WalletWipeState.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The wallet-wipe marker fails closed: only a marker confirmed absent is
 * [State.NONE]. Real file systems cannot be made to fail on demand, so the
 * indeterminate cases go through the [WalletWipeState.lookup] seam.
 */
class WalletWipeStateTest {
    @get:Rule val directory = TemporaryFolder()

    private val onDisk = WalletWipeState.lookup
    private val realWrite = WalletWipeState.writeMarker
    private val filesDir get() = File(directory.root, "files").apply { mkdirs() }
    private val noBackupDir get() = File(directory.root, "no_backup").apply { mkdirs() }
    private val marker get() = File(filesDir, WalletWipeState.MARKER_FILE_NAME)

    @After
    fun restoreLookup() {
        WalletWipeState.lookup = onDisk
        WalletWipeState.writeMarker = realWrite
    }

    @Test
    fun `a retried reset keeps the pending marker without rewriting it`() {
        assertTrue(WalletWipeState.begin(filesDir, noBackupDir))
        val bytes = marker.readBytes()
        var writes = 0
        WalletWipeState.writeMarker = { file, body ->
            writes++
            realWrite(file, body)
        }
        assertTrue(WalletWipeState.begin(filesDir, noBackupDir))
        assertEquals("a valid marker is never rewritten", 0, writes)
        assertTrue(bytes.contentEquals(marker.readBytes()))
        assertTrue(WalletWipeState.isPending(filesDir, noBackupDir))
    }

    @Test
    fun `a marker write that dies before the rename leaves no marker`() {
        WalletWipeState.writeMarker = { file, _ ->
            // As if killed after the temp was written: the destination is untouched.
            File(file.parentFile, file.name + ".tmp").writeText("v1\npartial")
            throw IOException("injected: killed mid-write")
        }
        assertFalse(WalletWipeState.begin(filesDir, noBackupDir))
        assertEquals(State.NONE, WalletWipeState.inspect(filesDir, noBackupDir))
    }

    @Test
    fun `a new marker is written whole and leaves no temp behind`() {
        assertTrue(WalletWipeState.begin(filesDir, noBackupDir))
        assertEquals(State.PENDING, WalletWipeState.inspect(filesDir, noBackupDir))
        assertFalse(File(filesDir, WalletWipeState.MARKER_FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `an undeterminable marker requires recovery, not none`() {
        WalletWipeState.lookup = { Entry.UNKNOWN }
        assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))
        assertFalse(WalletWipeState.isPending(filesDir, noBackupDir))
        assertFalse("must not write over an undeterminable marker", WalletWipeState.begin(filesDir, noBackupDir))
        assertFalse(marker.exists())
    }

    @Test
    fun `a lookup that throws requires recovery and does not propagate`() {
        WalletWipeState.lookup = { throw SecurityException("injected: no access") }
        assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))
        assertFalse(WalletWipeState.complete(filesDir))
    }

    @Test
    fun `a dangling link at the marker path is not none`() {
        Files.createSymbolicLink(marker.toPath(), File(filesDir, "nowhere").toPath())
        assertFalse("File.exists() follows the link", marker.exists())
        assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))
        assertFalse(WalletWipeState.begin(filesDir, noBackupDir))
        assertTrue(Files.isSymbolicLink(marker.toPath()))
        // complete() removes the link itself and only then reports it gone.
        assertTrue(WalletWipeState.complete(filesDir))
        assertEquals(State.NONE, WalletWipeState.inspect(filesDir, noBackupDir))
    }

    @Test
    fun `a link to a valid marker body is not read through`() {
        assertTrue(WalletWipeState.begin(filesDir, noBackupDir))
        val elsewhere = File(directory.root, "elsewhere")
        assertTrue(marker.renameTo(elsewhere))
        Files.createSymbolicLink(marker.toPath(), elsewhere.toPath())
        assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))
    }

    @Test
    fun `complete reports removal only when the absence is confirmed`() {
        assertTrue(WalletWipeState.begin(filesDir, noBackupDir))
        // The delete itself succeeds, but the check afterwards cannot tell.
        WalletWipeState.lookup = { Entry.UNKNOWN }
        assertFalse(WalletWipeState.complete(filesDir))
        assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))

        WalletWipeState.lookup = onDisk
        assertEquals(State.NONE, WalletWipeState.inspect(filesDir, noBackupDir))
        assertTrue("already absent", WalletWipeState.complete(filesDir))
    }

    @Test
    fun `an undeletable marker is not reported removed`() {
        // A non-empty directory at the marker path cannot be deleted.
        assertTrue(File(marker, "block").apply { parentFile?.mkdirs() }.createNewFile())
        assertFalse(WalletWipeState.complete(filesDir))
        assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))
    }

    @Test
    fun `a permanently undeterminable marker is answered every time without throwing`() {
        WalletWipeState.lookup = { throw IOException("injected: I/O error") }
        repeat(3) {
            assertEquals(State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupDir))
            assertFalse(WalletWipeState.begin(filesDir, noBackupDir))
            assertFalse(WalletWipeState.complete(filesDir))
        }
        assertFalse(marker.exists())
    }
}
