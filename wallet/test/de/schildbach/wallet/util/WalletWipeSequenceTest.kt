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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The reset defect these cover, from S22 (mainnet, 11.10.70):
 *
 *  22:38:23  the user confirms Reset Wallet; destruction starts
 *  22:39:11  auto-logout shows the lock screen — of a wallet whose file and
 *            keystore secrets are already deleted; the user unlocks back into it
 *  22:40:20  every destructive step is finally done and the wallet leaves memory
 *  22:40:24  the completion callback calls Fragment.startActivity on the
 *            SecurityFragment that asked for the reset two minutes earlier:
 *            IllegalStateException, process dies mid-wipe
 */
class WalletWipeSequenceTest {

    private fun newFilesDir(): File = Files.createTempDirectory("wipe-state").toFile()
    private fun newNoBackupFilesDir(): File = Files.createTempDirectory("wipe-state-nobackup").toFile()

    // ── WalletWipeState: the recovery marker ───────────────────────────

    @Test
    fun `marker survives until the wipe is recorded complete`() {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        assertFalse(WalletWipeState.isPending(filesDir, noBackupFilesDir))
        assertEquals(WalletWipeState.State.NONE, WalletWipeState.inspect(filesDir, noBackupFilesDir))

        assertTrue(WalletWipeState.begin(filesDir, noBackupFilesDir))
        assertTrue(WalletWipeState.isPending(filesDir, noBackupFilesDir))

        WalletWipeState.complete(filesDir)
        assertFalse(WalletWipeState.isPending(filesDir, noBackupFilesDir))
        assertEquals(WalletWipeState.State.NONE, WalletWipeState.inspect(filesDir, noBackupFilesDir))
    }

    @Test
    fun `a wipe restarted over an interrupted one keeps the marker`() {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        assertTrue(WalletWipeState.begin(filesDir, noBackupFilesDir))
        assertTrue(WalletWipeState.begin(filesDir, noBackupFilesDir))
        assertTrue(WalletWipeState.isPending(filesDir, noBackupFilesDir))
    }

    @Test
    fun `an empty legacy or planted marker requires recovery across launches`() {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        File(filesDir, WalletWipeState.MARKER_FILE_NAME).createNewFile()

        assertFalse(WalletWipeState.isPending(filesDir, noBackupFilesDir))
        repeat(2) {
            assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupFilesDir))
            assertTrue(File(filesDir, WalletWipeState.MARKER_FILE_NAME).exists())
        }
    }

    @Test
    fun `a marker restored without this install token is ignored`() {
        val filesDir = newFilesDir()
        val originalNoBackupFilesDir = newNoBackupFilesDir()
        val restoredNoBackupFilesDir = newNoBackupFilesDir()
        assertTrue(WalletWipeState.begin(filesDir, originalNoBackupFilesDir))

        assertFalse(WalletWipeState.isPending(filesDir, restoredNoBackupFilesDir))
        assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, restoredNoBackupFilesDir))
        assertTrue(File(filesDir, WalletWipeState.MARKER_FILE_NAME).exists())
    }

    @Test
    fun `invalid bodies with an existing token preserve recovery guard and wallet data`() {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        val wallet = File(filesDir, "wallet-protobuf-testnet").apply { writeText("wallet sentinel") }
        val backup = File(filesDir, "key-backup-protobuf-testnet").apply { writeText("backup sentinel") }
        assertTrue(WalletWipeState.begin(filesDir, noBackupFilesDir))
        val marker = File(filesDir, WalletWipeState.MARKER_FILE_NAME)
        val valid = marker.readText()
        val mismatched = valid.replaceRange(3, 4, if (valid[3] == '0') "1" else "0")
        for (body in listOf("", "malformed", valid.replace("v1", "v2"), mismatched, "x".repeat(1024))) {
            marker.writeText(body)
            repeat(2) {
                assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupFilesDir))
                assertFalse(WalletWipeState.isPending(filesDir, noBackupFilesDir))
            }
            assertEquals(body, marker.readText())
            assertEquals("wallet sentinel", wallet.readText())
            assertEquals("backup sentinel", backup.readText())
            runBlocking {
                WalletWipeSequence.finish(
                    pending = { WalletWipeState.isPending(filesDir, noBackupFilesDir) },
                    detachWallet = { throw AssertionError("must not detach an unverified wallet") },
                    destroy = { throw AssertionError("must not destroy an unverified wallet") },
                    markComplete = { throw AssertionError("must preserve the recovery guard") }
                )
            }
        }
    }

    @Test
    fun `oversized token and directory marker require recovery`() {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        assertTrue(WalletWipeState.begin(filesDir, noBackupFilesDir))
        File(noBackupFilesDir, "wallet-wipe.install-token").writeText("a".repeat(1024))
        assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupFilesDir))
        assertTrue(WalletWipeState.begin(filesDir, noBackupFilesDir))
        val marker = File(filesDir, WalletWipeState.MARKER_FILE_NAME)
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupFilesDir))
        assertTrue(marker.isDirectory)
    }

    @Test
    fun `legacy marker guards a partial wipe with only the key backup remaining`() {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        val backup = File(filesDir, "key-backup-protobuf-testnet").apply { writeText("remaining keys") }
        File(filesDir, WalletWipeState.MARKER_FILE_NAME).createNewFile()
        repeat(2) {
            assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupFilesDir))
            assertEquals("remaining keys", backup.readText())
        }
    }

    @Test
    fun `marker handling never throws on an unusable files dir`() {
        // A file where the directory should be: every call has to fail soft —
        // this is the recovery channel, it must not be able to kill a launch.
        val notADir = File.createTempFile("wipe-state", ".notdir")
        val noBackupFilesDir = newNoBackupFilesDir()
        assertFalse(WalletWipeState.begin(notADir, noBackupFilesDir))
        assertFalse(WalletWipeState.isPending(notADir, noBackupFilesDir))
        WalletWipeState.complete(notADir)
    }

    // ── WalletWipeSequence: the order ──────────────────────────────────

    @Test
    fun `nothing is destroyed until the wipe is recorded and the ui has been handed off`() {
        val order = mutableListOf<String>()
        WalletWipeSequence.begin(
            markPending = { order.add("mark"); true },
            handOffUi = { order.add("ui") }
        )
        runBlocking {
            WalletWipeSequence.finish(
                pending = { true },
                detachWallet = { order.add("detach") },
                destroy = { order.add("destroy") },
                markComplete = { order.add("complete") }
            )
        }
        assertEquals(listOf("mark", "ui", "detach", "destroy", "complete"), order)
    }

    @Test
    fun `the wipe is still handed off to the ui when the marker cannot be written`() {
        // Losing the marker costs mid-wipe recovery, not the wipe: the user
        // asked for a reset and must not be left on the wallet screen.
        var handedOff = false
        WalletWipeSequence.begin(markPending = { false }, handOffUi = { handedOff = true })
        assertTrue(handedOff)
    }

    @Test
    fun `the wallet leaves memory before its data is destroyed`() {
        val order = mutableListOf<String>()
        runBlocking {
            WalletWipeSequence.finish(
                pending = { true },
                detachWallet = { order.add("detach") },
                destroy = { order.add("destroy") },
                markComplete = { }
            )
        }
        assertEquals(listOf("detach", "destroy"), order)
    }

    @Test
    fun `a teardown that no wipe asked for destroys nothing`() {
        // ACTION_WIPE_WALLET reaching the service without the user having
        // confirmed a reset must not delete a wallet.
        var touched = false
        val ran = runBlocking {
            WalletWipeSequence.finish(
                pending = { false },
                detachWallet = { touched = true },
                destroy = { touched = true },
                markComplete = { touched = true }
            )
        }
        assertFalse(ran)
        assertFalse(touched)
    }

    @Test
    fun `a failed destruction leaves the marker for the next launch`() {
        var completed = false
        try {
            runBlocking {
                WalletWipeSequence.finish(
                    pending = { true },
                    detachWallet = { },
                    destroy = { throw IOException("store gone") },
                    markComplete = { completed = true }
                )
            }
            fail("the failure must reach the caller")
        } catch (expected: IOException) {
            // expected
        }
        assertFalse("clearing the marker early is the half-wiped state itself", completed)
    }

    // ── The wipe must suspend, never block ─────────────────────────────

    @Test
    fun `waiting for the destruction does not occupy the dispatcher thread`() {
        // The regression: clearDatabases ran under runBlocking on a
        // DefaultDispatcher worker and held it for the ~2 minutes the SDK
        // cleanup took. Here the wipe and a second coroutine share ONE thread;
        // the second one can only run if the wipe suspended rather than
        // blocking.
        val oneThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runBlocking {
                val destroyEntered = CompletableDeferred<Unit>()
                val releaseDestroy = CompletableDeferred<Unit>()
                val otherWorkRan = CompletableDeferred<Unit>()

                val wipe = launch(oneThread) {
                    WalletWipeSequence.finish(
                        pending = { true },
                        detachWallet = { },
                        destroy = {
                            destroyEntered.complete(Unit)
                            releaseDestroy.await()
                        },
                        markComplete = { }
                    )
                }

                destroyEntered.await()
                launch(oneThread) { otherWorkRan.complete(Unit) }
                withTimeout(5_000) { otherWorkRan.await() }

                releaseDestroy.complete(Unit)
                wipe.join()
            }
        } finally {
            oneThread.close()
        }
    }
}
