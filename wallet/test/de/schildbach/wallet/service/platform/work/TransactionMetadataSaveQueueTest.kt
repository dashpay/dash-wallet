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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun discardPending_cancelsTheRunningSaveAndDropsTheQueuedOnes() = runTest(dispatcher) {
        // what Reset Wallet does: nothing from the old wallet may land after it
        val ran = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        queue.submit {
            ran += "running: started"
            gate.await()
            ran += "running: finished"
        }
        queue.submit { ran += "queued" }
        advanceUntilIdle()

        queue.discardPending()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("running: started"), ran)
    }

    @Test
    fun savesSubmittedAfterADiscardRun() = runTest(dispatcher) {
        val ran = mutableListOf<String>()
        queue.submit { ran += "old wallet" }
        queue.discardPending()

        queue.submit { ran += "new wallet" }
        advanceUntilIdle()

        assertEquals(listOf("new wallet"), ran)
    }
}
