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
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * The wallet-wipe marker fails closed: only a marker confirmed absent is
 * [State.NONE]. Real file systems cannot be made to fail on demand, so the
 * indeterminate cases go through the [WalletWipeState.lookup] seam.
 *
 * [WalletWipeState.pendingOrNull] gates resuming the metadata save queue
 * after a wipe, so it must separate "marker absent" from "could not check"
 * (and from "unverified marker").
 */
class WalletWipeStateTest {
    @get:Rule val directory = TemporaryFolder()

    private val onDisk = WalletWipeState.lookup
    private val filesDir get() = File(directory.root, "files").apply { mkdirs() }
    private val noBackupDir get() = File(directory.root, "no_backup").apply { mkdirs() }
    private val marker get() = File(filesDir, WalletWipeState.MARKER_FILE_NAME)

    @After
    fun restoreLookup() {
        WalletWipeState.lookup = onDisk
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

    @Test
    fun `pendingOrNull is true when the marker is present`() {
        assertTrue(WalletWipeState.begin(filesDir, noBackupDir))

        assertEquals(true, WalletWipeState.pendingOrNull(filesDir, noBackupDir))
    }

    @Test
    fun `pendingOrNull is false when the marker is absent`() {
        assertEquals(false, WalletWipeState.pendingOrNull(filesDir, noBackupDir))
    }

    @Test
    fun `pendingOrNull is false once the wipe is recorded complete`() {
        WalletWipeState.begin(filesDir, noBackupDir)
        WalletWipeState.complete(filesDir)

        assertEquals(false, WalletWipeState.pendingOrNull(filesDir, noBackupDir))
    }

    @Test
    fun `pendingOrNull is null when the files dir itself does not exist`() {
        // Fail closed: without the directory the marker's absence is not
        // established, which inspect() reports as RECOVERY_REQUIRED.
        val missing = File(directory.root, "missing")
        assertFalse(missing.exists())

        assertNull(WalletWipeState.pendingOrNull(missing, noBackupDir))
    }

    @Test
    fun `pendingOrNull is null for an unverified marker`() {
        marker.writeText("not a valid marker")

        assertNull(WalletWipeState.pendingOrNull(filesDir, noBackupDir))
    }

    @Test
    fun `pendingOrNull is null when the lookup fails`() {
        // A regular file where a parent directory should be: the lookup fails
        // with ENOTDIR rather than finding nothing.
        val brokenFilesDir = File(directory.newFile("not-a-dir"), "files")
        val brokenMarker = File(brokenFilesDir, WalletWipeState.MARKER_FILE_NAME)

        // The precondition this test depends on: the failure is reported as
        // something other than "no such file", or it would not reach the
        // error path at all.
        try {
            Files.readAttributes(brokenMarker.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            fail("lookup unexpectedly succeeded")
        } catch (e: NoSuchFileException) {
            fail("lookup reported absent, so this does not exercise the error path: $e")
        } catch (e: FileSystemException) {
            // expected: "Not a directory"
        }
        // File.exists() swallows the same failure and reports "absent" — a
        // check built on it would have let the queue resume here.
        assertFalse(brokenMarker.exists())

        assertNull(WalletWipeState.pendingOrNull(brokenFilesDir, noBackupDir))
    }
}
