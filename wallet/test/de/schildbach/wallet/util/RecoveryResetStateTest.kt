package de.schildbach.wallet.util

import de.schildbach.wallet.util.RecoveryResetState.Marker
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
 * The recovery-reset marker fails closed: only a marker confirmed absent
 * means no reset is owed. Real file systems cannot be made to fail on demand,
 * so the UNKNOWN cases go through the [RecoveryResetState.inspect] seam.
 */
class RecoveryResetStateTest {
    @get:Rule val directory = TemporaryFolder()

    private val onDisk = RecoveryResetState.inspect
    private val marker get() = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)

    @After
    fun restoreInspection() {
        RecoveryResetState.inspect = onDisk
    }

    @Test
    fun `absent, armed and completed markers are told apart`() {
        assertEquals(Marker.ABSENT, RecoveryResetState.state(directory.root))
        assertFalse(RecoveryResetState.isPending(directory.root))

        RecoveryResetState.arm(directory.root)
        assertEquals(Marker.PENDING, RecoveryResetState.state(directory.root))
        assertTrue(RecoveryResetState.isPending(directory.root))

        assertTrue(RecoveryResetState.complete(directory.root))
        assertEquals(Marker.ABSENT, RecoveryResetState.state(directory.root))
        assertTrue("already absent", RecoveryResetState.complete(directory.root))
    }

    @Test
    fun `a dangling link at the marker path is pending, not absent`() {
        // File.exists() follows the link and answers false here.
        Files.createSymbolicLink(marker.toPath(), File(directory.root, "nowhere").toPath())
        assertFalse(marker.exists())
        assertEquals(Marker.PENDING, RecoveryResetState.state(directory.root))
        assertTrue(RecoveryResetState.isPending(directory.root))
        assertTrue(RecoveryResetState.complete(directory.root))
        assertEquals(Marker.ABSENT, RecoveryResetState.state(directory.root))
    }

    @Test
    fun `an undeletable marker keeps the reset owed`() {
        // A non-empty directory at the marker path cannot be deleted.
        assertTrue(File(marker, "block").apply { parentFile?.mkdirs() }.createNewFile())
        assertFalse(RecoveryResetState.complete(directory.root))
        assertEquals(Marker.PENDING, RecoveryResetState.state(directory.root))
    }

    @Test
    fun `an undeterminable marker counts as pending`() {
        RecoveryResetState.inspect = { Marker.UNKNOWN }
        assertEquals(Marker.UNKNOWN, RecoveryResetState.state(directory.root))
        assertTrue(RecoveryResetState.isPending(directory.root))
    }

    @Test
    fun `an inspection that throws counts as pending and does not propagate`() {
        RecoveryResetState.inspect = { throw SecurityException("injected: no access") }
        assertEquals(Marker.UNKNOWN, RecoveryResetState.state(directory.root))
        assertTrue(RecoveryResetState.isPending(directory.root))
        assertFalse(RecoveryResetState.complete(directory.root))
    }

    @Test
    fun `a delete whose result cannot be verified does not complete the reset`() {
        RecoveryResetState.arm(directory.root)
        // The delete itself succeeds, but the check afterwards cannot tell.
        RecoveryResetState.inspect = { Marker.UNKNOWN }
        assertFalse(RecoveryResetState.complete(directory.root))
        assertFalse(marker.exists())
        assertTrue(RecoveryResetState.isPending(directory.root))

        // Once the file system answers again, the absence is confirmed.
        RecoveryResetState.inspect = onDisk
        assertFalse(RecoveryResetState.isPending(directory.root))
        assertTrue(RecoveryResetState.complete(directory.root))
    }

    @Test
    fun `a permanently undeterminable marker is answered every time without throwing`() {
        RecoveryResetState.inspect = { throw IOException("injected: I/O error") }
        repeat(RecoveryResetState.MAX_FAILED_ATTEMPTS + 2) {
            assertTrue(RecoveryResetState.isPending(directory.root))
            assertFalse(RecoveryResetState.complete(directory.root))
            // Unreadable, so its retries cannot be counted: exhausted.
            assertEquals(
                RecoveryResetState.MAX_FAILED_ATTEMPTS,
                RecoveryResetState.recordFailedAttempt(directory.root)
            )
        }
        assertFalse("no marker is created by the failed bookkeeping", marker.exists())
    }
}
