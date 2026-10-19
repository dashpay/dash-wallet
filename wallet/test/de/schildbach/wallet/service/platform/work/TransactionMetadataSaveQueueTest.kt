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

package de.schildbach.wallet.service.platform.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionMetadataSaveQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queue = TransactionMetadataSaveQueue(applicationScope)

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun savesRunInSubmissionOrder() = runTest(dispatcher) {
        val ran = mutableListOf<Int>()

        (1..3).forEach { n -> queue.submit { ran += n } }
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), ran)
    }

    @Test
    fun aCancelledSaveDoesNotStopTheQueue() = runTest(dispatcher) {
        val ran = mutableListOf<String>()

        queue.submit { throw CancellationException("save cancelled on its own") }
        queue.submit { ran += "after" }
        advanceUntilIdle()

        assertEquals(listOf("after"), ran)
    }

    @Test
    fun aFailedSaveDoesNotStopTheQueue() = runTest(dispatcher) {
        val ran = mutableListOf<String>()

        queue.submit { throw IllegalStateException("disk full") }
        queue.submit { ran += "after" }
        advanceUntilIdle()

        assertEquals(listOf("after"), ran)
    }

    @Test
    fun pause_stopsTheRunningSaveAndDropsTheQueuedOnes() = runTest(dispatcher) {
        // what confirming Reset Wallet does, on the main thread
        val ran = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        queue.submit {
            ran += "running: started"
            gate.await()
            ran += "running: finished"
        }
        queue.submit { ran += "queued" }
        advanceUntilIdle()

        val stopping = queue.pause()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("running: started"), ran)
        assertTrue(stopping?.isCancelled == true)
    }

    @Test
    fun commit_isSkippedForASaveThatOutlivedAPause() = runTest(dispatcher) {
        // Cancellation is cooperative: a save past its last suspension point
        // still reaches its final step. NonCancellable stands in for that.
        val committed = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        queue.submit {
            withContext(NonCancellable) { gate.await() }
            commit { committed += "publish enqueued" }
        }
        advanceUntilIdle()

        queue.pause()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun commit_runsForACurrentSave() = runTest(dispatcher) {
        var result: String? = null

        queue.submit { result = commit { "publish enqueued" } }
        advanceUntilIdle()

        assertEquals("publish enqueued", result)
    }

    @Test
    fun submit_isRefusedWhilePausedAndAcceptedAfterResume() = runTest(dispatcher) {
        val ran = mutableListOf<String>()
        queue.pause()

        assertFalse(queue.submit { ran += "during the wipe" })
        queue.resume()
        assertTrue(queue.submit { ran += "next wallet" })
        advanceUntilIdle()

        assertEquals(listOf("next wallet"), ran)
    }

    @Test
    fun submitAndAwait_runsBehindQueuedSavesAndReturnsTheResult() = runTest(dispatcher) {
        val ran = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        queue.submit {
            gate.await()
            ran += "earlier save"
        }

        val result = async { queue.submitAndAwait { ran += "publish now"; "work-id" } }
        advanceUntilIdle()
        assertFalse(result.isCompleted)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("work-id", result.await())
        assertEquals(listOf("earlier save", "publish now"), ran)
    }

    @Test
    fun submitAndAwait_failsInsteadOfHangingWhenAPauseDropsIt() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        queue.submit { gate.await() }
        val result = async { runCatching { queue.submitAndAwait { "work-id" } } }
        advanceUntilIdle()

        queue.pause()
        advanceUntilIdle()

        assertTrue(result.await().exceptionOrNull() is TransactionMetadataSaveQueue.SaveDiscardedException)
    }

    @Test
    fun submitAndAwait_isRefusedWhilePaused() = runTest(dispatcher) {
        queue.pause()

        val outcome = runCatching { queue.submitAndAwait { "work-id" } }

        assertTrue(outcome.exceptionOrNull() is TransactionMetadataSaveQueue.SaveDiscardedException)
    }
}
