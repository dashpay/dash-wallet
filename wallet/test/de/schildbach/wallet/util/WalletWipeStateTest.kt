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

import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [WalletWipeState.pendingOrNull] gates resuming the metadata save queue
 * after a wipe, so it must separate "marker absent" from "could not check".
 */
class WalletWipeStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `pendingOrNull is true when the marker is present`() {
        val filesDir = tmp.newFolder("files")
        assertTrue(WalletWipeState.begin(filesDir))

        assertEquals(true, WalletWipeState.pendingOrNull(filesDir))
    }

    @Test
    fun `pendingOrNull is false when the marker is absent`() {
        val filesDir = tmp.newFolder("files")

        assertEquals(false, WalletWipeState.pendingOrNull(filesDir))
    }

    @Test
    fun `pendingOrNull is false once the wipe is recorded complete`() {
        val filesDir = tmp.newFolder("files")
        WalletWipeState.begin(filesDir)
        WalletWipeState.complete(filesDir)

        assertEquals(false, WalletWipeState.pendingOrNull(filesDir))
    }

    @Test
    fun `pendingOrNull is false when the files dir itself does not exist`() {
        // No directory, so genuinely no marker: this is "absent", not a failure.
        val filesDir = File(tmp.root, "missing")
        assertFalse(filesDir.exists())

        assertEquals(false, WalletWipeState.pendingOrNull(filesDir))
    }

    @Test
    fun `pendingOrNull is null when the lookup fails`() {
        // A regular file where a parent directory should be: the lookup fails
        // with ENOTDIR rather than finding nothing.
        val filesDir = File(tmp.newFile("not-a-dir"), "files")
        val marker = File(filesDir, WalletWipeState.MARKER_FILE_NAME)

        // The precondition this test depends on: the failure is reported as
        // something other than "no such file", or it would not reach the
        // error path at all.
        try {
            Files.readAttributes(marker.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            fail("lookup unexpectedly succeeded")
        } catch (e: NoSuchFileException) {
            fail("lookup reported absent, so this does not exercise the error path: $e")
        } catch (e: FileSystemException) {
            // expected: "Not a directory"
        }
        // File.exists() swallows the same failure and reports "absent" — the
        // old implementation would have let the queue resume here.
        assertFalse(marker.exists())

        assertNull(WalletWipeState.pendingOrNull(filesDir))
    }
}
