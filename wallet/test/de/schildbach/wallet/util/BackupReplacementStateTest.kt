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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The backup-replacement marker is cleared only by the generation that armed
 * or observed it, so a repair of one wallet cannot discharge the obligation
 * of a replacement armed meanwhile (review, PR #1576).
 */
class BackupReplacementStateTest {
    @get:Rule val directory = TemporaryFolder()

    private val marker get() = File(directory.root, BackupReplacementState.MARKER_FILE_NAME)

    @Test
    fun `an arm is cleared by its own generation`() {
        val armed = BackupReplacementState.arm(directory.root)
        assertTrue(BackupReplacementState.isPending(directory.root))

        assertTrue(BackupReplacementState.complete(directory.root, armed))
        assertFalse(marker.exists())
        assertNull(BackupReplacementState.observe(directory.root))
    }

    @Test
    fun `a stale generation does not clear a newer arm`() {
        val first = BackupReplacementState.arm(directory.root)
        val observed = requireNotNull(BackupReplacementState.observe(directory.root))
        val second = BackupReplacementState.arm(directory.root)

        assertFalse(BackupReplacementState.complete(directory.root, first))
        assertFalse(BackupReplacementState.complete(directory.root, observed))
        assertTrue("the newer arm is still owed", marker.exists())

        assertTrue(BackupReplacementState.complete(directory.root, second))
        assertFalse(marker.exists())
    }

    @Test
    fun `a completion after the marker is gone reports it cleared`() {
        val armed = BackupReplacementState.arm(directory.root)
        BackupReplacementState.discard(directory.root)

        assertTrue(BackupReplacementState.complete(directory.root, armed))
    }

    @Test
    fun `a legacy empty marker is cleared only while it is still empty`() {
        marker.createNewFile()
        val legacy = requireNotNull(BackupReplacementState.observe(directory.root))
        assertTrue(BackupReplacementState.complete(directory.root, legacy))
        assertFalse(marker.exists())

        marker.createNewFile()
        val staleLegacy = requireNotNull(BackupReplacementState.observe(directory.root))
        val armed = BackupReplacementState.arm(directory.root)
        assertFalse(BackupReplacementState.complete(directory.root, staleLegacy))
        assertTrue(marker.exists())
        assertTrue(BackupReplacementState.complete(directory.root, armed))
    }

    @Test
    fun `an unreadable marker is never cleared by a completion`() {
        // A directory at the marker's path: present, but its content cannot be read.
        assertTrue(marker.mkdir())
        val unreadable = BackupReplacementState.observe(directory.root)
        assertNotNull("present, so owed", unreadable)

        assertFalse(BackupReplacementState.complete(directory.root, requireNotNull(unreadable)))
        assertTrue(marker.exists())
        assertTrue(BackupReplacementState.isPending(directory.root))
    }

    @Test
    fun `discard removes any generation`() {
        BackupReplacementState.arm(directory.root)

        assertTrue(BackupReplacementState.discard(directory.root))
        assertFalse(BackupReplacementState.isPending(directory.root))
    }

    @Test
    fun `an arm leaves no temp behind`() {
        BackupReplacementState.arm(directory.root)

        assertTrue(directory.root.listFiles()!!.all { it.name == BackupReplacementState.MARKER_FILE_NAME })
    }
}
