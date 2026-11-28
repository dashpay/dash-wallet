/*
 * Copyright 2026 the original author or authors.
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

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The key backup is the only fallback `WalletApplication.loadWalletFromProtobuf()` has when the
 * primary wallet fails to parse, so a half-written backup turns a recoverable load failure into a
 * startup crash-loop. These tests pin the crash-safety property: the destination is either the old
 * complete file or the new complete file, never a partial one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class AtomicFileWriterTest {

    private lateinit var context: Context
    private lateinit var destFile: File
    private lateinit var tempFile: File

    private val name = "key-backup-protobuf"
    private val oldContent = "PREVIOUS-GOOD-BACKUP".toByteArray()
    private val newContent = "NEW-GOOD-BACKUP-WHICH-IS-LONGER".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        destFile = File(context.filesDir, name)
        tempFile = File(context.filesDir, name + AtomicFileWriter.TEMP_SUFFIX)
        destFile.delete()
        tempFile.delete()
    }

    @Test
    fun `writes the content and leaves no temp behind`() {
        AtomicFileWriter.write(context, name) { out -> out.write(newContent) }

        assertTrue("destination should exist", destFile.exists())
        assertArrayEquals(newContent, destFile.readBytes())
        assertFalse("temp file should not survive a successful write", tempFile.exists())
    }

    @Test
    fun `overwrites an existing file completely, not in place`() {
        destFile.writeBytes(oldContent)

        AtomicFileWriter.write(context, name) { out -> out.write(newContent) }

        assertArrayEquals(newContent, destFile.readBytes())
        assertFalse(tempFile.exists())
    }

    @Test
    fun `a failure mid-write leaves the previous backup intact`() {
        destFile.writeBytes(oldContent)

        val failure = try {
            AtomicFileWriter.write(context, name) { out ->
                // Simulate a write that dies part-way through, the way a killed process would.
                out.write(newContent, 0, 5)
                throw IOException("disk full")
            }
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("disk full", failure?.message)
        assertTrue("destination must still exist", destFile.exists())
        assertArrayEquals(
            "the previous backup must be byte-for-byte untouched",
            oldContent,
            destFile.readBytes()
        )
        assertFalse("the partial temp must be cleaned up", tempFile.exists())
    }

    @Test
    fun `a failure with no previous backup leaves no partial file`() {
        assertFalse(destFile.exists())

        try {
            AtomicFileWriter.write(context, name) { out ->
                out.write(newContent, 0, 5)
                throw IOException("disk full")
            }
        } catch (e: IOException) {
            // expected
        }

        assertFalse("must not publish a truncated backup", destFile.exists())
        assertFalse(tempFile.exists())
    }

    @Test
    fun `temp name is swept by the cleanupFiles tmp filter`() {
        // WalletApplication.cleanupFiles() deletes files ending in ".tmp"; the temp name must stay
        // inside that sweep so an abandoned temp is garbage-collected on the next launch.
        assertTrue((name + AtomicFileWriter.TEMP_SUFFIX).endsWith(".tmp"))
    }
}
