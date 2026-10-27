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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

        assertFalse(WalletWipeState.begin(filesDir, noBackupFilesDir))
        assertFalse(File(noBackupFilesDir, "wallet-wipe.install-token").exists())
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

        val originalMarker = File(filesDir, WalletWipeState.MARKER_FILE_NAME).readText()
        assertFalse(WalletWipeState.begin(filesDir, restoredNoBackupFilesDir))
        assertEquals(originalMarker, File(filesDir, WalletWipeState.MARKER_FILE_NAME).readText())
        assertFalse(File(restoredNoBackupFilesDir, "wallet-wipe.install-token").exists())
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
            val token = File(noBackupFilesDir, "wallet-wipe.install-token")
            val tokenBefore = token.readText()
            assertFalse(WalletWipeState.begin(filesDir, noBackupFilesDir))
            assertEquals(tokenBefore, token.readText())
            repeat(2) {
                assertEquals(WalletWipeState.State.RECOVERY_REQUIRED, WalletWipeState.inspect(filesDir, noBackupFilesDir))
                assertFalse(WalletWipeState.isPending(filesDir, noBackupFilesDir))
            }
            assertEquals(body, marker.readText())
            assertEquals("wallet sentinel", wallet.readText())
            assertEquals("backup sentinel", backup.readText())
            runBlocking {
                WalletWipeSequence.finish(
                    pending = { WalletWipeState.pendingState(filesDir, noBackupFilesDir) },
                    fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
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
        assertFalse(WalletWipeState.begin(filesDir, noBackupFilesDir))
        assertEquals("a".repeat(1024), File(noBackupFilesDir, "wallet-wipe.install-token").readText())
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
        val confirmed = WalletWipeSequence.begin(
            markPending = { order.add("mark"); true },
            fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
            handOffUi = { order.add("ui") }
        )
        assertTrue(confirmed)
        runBlocking {
            WalletWipeSequence.finish(
                beginConfirmed = confirmed,
                pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
                detachWallet = { order.add("detach") },
                destroy = { order.add("destroy") },
                markComplete = { order.add("complete") }
            )
        }
        assertEquals(listOf("mark", "ui", "detach", "destroy", "complete"), order)
    }

    @Test
    fun `a marker write failure refuses the reset - no hand-off, wallet intact, barrier down`() {
        // Handing off without a marker left the old wallet installed behind onboarding: finish()
        // found no marker and destroyed nothing while the user believed the wallet was reset.
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        val wallet = File(filesDir, "wallet-protobuf-testnet").apply { writeText("wallet sentinel") }
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val previousWrite = WalletWipeState.writeMarker
        WalletWipeState.writeMarker = { _, _ -> throw IOException("disk full") }
        var handedOff = false
        val confirmed = try {
            WalletWipeSequence.begin(
                markPending = { WalletWipeState.begin(filesDir, noBackupFilesDir) },
                fundingBarrier = lock,
                handOffUi = { handedOff = true }
            )
        } finally {
            WalletWipeState.writeMarker = previousWrite
        }
        assertFalse(confirmed)
        assertFalse("handed off to onboarding without a marker", handedOff)
        assertFalse(lock.isWipeBarrierRaised)
        assertEquals("next", runBlocking { withTimeout(2_000) { lock.withShared { "next" } } })
        assertEquals("wallet sentinel", wallet.readText())
        assertEquals(WalletWipeState.Pending.NOT_PENDING, WalletWipeState.pendingState(filesDir, noBackupFilesDir))
    }

    @Test
    fun `a marker that writes but does not read back refuses the reset`() {
        var handedOff = false
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        assertFalse(WalletWipeSequence.begin(markPending = { false }, fundingBarrier = lock, handOffUi = { handedOff = true }))
        assertFalse(WalletWipeSequence.begin(markPending = { throw IOException("x") }, fundingBarrier = lock, handOffUi = { handedOff = true }))
        assertFalse(handedOff)
        assertFalse(lock.isWipeBarrierRaised)
    }

    @Test
    fun `a refused reset leaves a barrier an earlier wipe raised up`() {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        lock.raiseWipeBarrier()
        assertFalse(WalletWipeSequence.begin(markPending = { false }, fundingBarrier = lock, handOffUi = { fail("handed off") }))
        assertTrue(lock.isWipeBarrierRaised)
    }

    @Test
    fun `a confirmed begin raises the barrier before the hand-off`() {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        var barrierAtHandOff = false
        assertTrue(
            WalletWipeSequence.begin(
                markPending = { true },
                fundingBarrier = lock,
                handOffUi = { barrierAtHandOff = lock.isWipeBarrierRaised }
            )
        )
        assertTrue(barrierAtHandOff)
    }

    @Test
    fun `an unreadable marker after a confirmed begin still wipes`() = runBlocking {
        val filesDir = newFilesDir()
        val noBackupFilesDir = newNoBackupFilesDir()
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val confirmed = WalletWipeSequence.begin(
            markPending = { WalletWipeState.begin(filesDir, noBackupFilesDir) },
            fundingBarrier = lock,
            handOffUi = { }
        )
        assertTrue(confirmed)
        val previousLookup = WalletWipeState.lookup
        WalletWipeState.lookup = { WalletWipeState.Entry.UNKNOWN } // the marker can no longer be read
        val events = mutableListOf<String>()
        val ran = try {
            assertEquals(WalletWipeState.Pending.UNKNOWN, WalletWipeState.pendingState(filesDir, noBackupFilesDir))
            WalletWipeSequence.finish(
                beginConfirmed = confirmed,
                pending = { WalletWipeState.pendingState(filesDir, noBackupFilesDir) },
                fundingBarrier = lock,
                detachWallet = { events += "detach" },
                destroy = { events += "destroy" },
                markComplete = { events += "complete" }
            )
        } finally {
            WalletWipeState.lookup = previousLookup
        }
        assertTrue(ran)
        assertEquals(listOf("detach", "destroy", "complete"), events)
        assertFalse(lock.isWipeBarrierRaised)
    }

    @Test
    fun `an unknown marker on resume keeps the barrier and does not report the wipe complete`() = runBlocking {
        // resumeInterruptedWipe / a service teardown with no confirmed begin in hand.
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        lock.raiseWipeBarrier()
        val ran = WalletWipeSequence.finish(
            beginConfirmed = false,
            pending = { WalletWipeState.Pending.UNKNOWN },
            fundingBarrier = lock,
            detachWallet = { fail("detached") },
            destroy = { fail("destroyed") },
            markComplete = { fail("marked complete") }
        )
        assertFalse(ran)
        assertTrue(lock.isWipeBarrierRaised)
        assertTrue(
            runCatching { lock.withShared { "paid" } }.exceptionOrNull() is
                de.schildbach.wallet.payments.WalletWipedException
        )
        // a marker read that throws is the same unknown
        val ranOnThrow = WalletWipeSequence.finish(
            pending = { throw IOException("unreadable") },
            fundingBarrier = lock,
            detachWallet = { fail("detached") },
            destroy = { fail("destroyed") },
            markComplete = { fail("marked complete") }
        )
        assertFalse(ranOnThrow)
        assertTrue(lock.isWipeBarrierRaised)
    }

    @Test
    fun `the wallet leaves memory before its data is destroyed`() {
        val order = mutableListOf<String>()
        runBlocking {
            WalletWipeSequence.finish(
                pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
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
                pending = { de.schildbach.wallet.util.WalletWipeState.Pending.NOT_PENDING },
                fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
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
                    pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                    fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
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
                        pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                        fundingBarrier = de.schildbach.wallet.payments.FundingSelectionLock(),
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

    // ── the payment paths' wipe barrier ────────────────────────────────

    @Test
    fun `a wipe with a payment hold in flight drains it first and deadlocks on nothing its teardown awaits`() =
        runBlocking {
            val lock = de.schildbach.wallet.payments.FundingSelectionLock()
            // a send under way when the user confirms the reset (waiting on the network)
            val holding = CompletableDeferred<Unit>()
            val send = async(Dispatchers.Default) {
                runCatching {
                    lock.withShared {
                        holding.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            holding.await()
            // app-scoped work that keeps asking for holds (the mixed-funds migration, a username's
            // asset-lock funding): the teardown cancels and joins it
            val worker = launch(Dispatchers.Default) {
                while (isActive) {
                    lock.selectIfAllowed({ false }) { "selected" }
                    delay(5)
                }
            }

            lock.raiseWipeBarrier() // Reset Wallet starts
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            val finished = withTimeout(10_000) {
                WalletWipeSequence.finish(
                    pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                    fundingBarrier = lock,
                    detachWallet = { events += "detach, send over=${send.isCompleted}" },
                    destroy = {
                        // the SDK stop, the migration stop, the verifier's job drain: each joins
                        // work that may be asking for a hold right now; none of it may wait
                        val asked = async(Dispatchers.Default) { runCatching { lock.withShared { "late" } } }
                        events += "teardown hold refused=${asked.await().exceptionOrNull() is
                            de.schildbach.wallet.payments.WalletWipedException}"
                        worker.cancelAndJoin()
                        events += "reactivation=${lock.tryExclusive { "reactivated" }}"
                        // the verifier's clear, a wipe listener, inside the barrier already up
                        lock.exclusiveForWipe { events += "verifier clear" }
                    },
                    markComplete = { events += "complete" }
                )
            }

            assertTrue(finished)
            assertEquals(
                listOf(
                    "detach, send over=true",
                    "teardown hold refused=true",
                    "reactivation=null",
                    "verifier clear",
                    "complete"
                ),
                events
            )
            assertTrue(
                "${send.await()}",
                send.await().exceptionOrNull() is de.schildbach.wallet.payments.WalletWipedCancellationException
            )
            // the old wallet is gone: the next wallet's payments go through
            assertFalse(lock.isWipeBarrierRaised)
            assertEquals("next", withTimeout(2_000) { lock.withShared { "next" } })
        }

    @Test
    fun `a wipe that fails keeps the payment paths refused`() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        lock.raiseWipeBarrier()
        try {
            WalletWipeSequence.finish(
                pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                fundingBarrier = lock,
                detachWallet = { },
                destroy = { throw IOException("keystore delete failed") },
                markComplete = { fail("marked complete") }
            )
            fail("a failed wipe returned")
        } catch (expected: IOException) {
            // the marker stays; the next launch finishes the wipe
        }
        assertTrue(lock.isWipeBarrierRaised)
        assertTrue(
            runCatching { lock.withShared { "paid" } }.exceptionOrNull() is
                de.schildbach.wallet.payments.WalletWipedException
        )
    }

    @Test
    fun `a stop with no wipe recorded lowers the barrier and destroys nothing`() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        lock.raiseWipeBarrier()
        val ran = WalletWipeSequence.finish(
            pending = { de.schildbach.wallet.util.WalletWipeState.Pending.NOT_PENDING },
            fundingBarrier = lock,
            detachWallet = { fail("detached") },
            destroy = { fail("destroyed") },
            markComplete = { fail("marked complete") }
        )
        assertFalse(ran)
        assertFalse(lock.isWipeBarrierRaised)
    }
}
