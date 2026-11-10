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

package de.schildbach.wallet.ui.payments.internal

import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.FeeEstimate
import de.schildbach.wallet.service.platform.sdk.InternalTransferService
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.ui.payments.send.ExternalSendInFlightMarker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [InternalTransferExecutor]'s durable-marker contract, mirroring the External Send executor's tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class InternalTransferExecutorTest {

    private val request = InternalTransferRequest(SendBalance.SHIELDED, SendBalance.CORE, Dash.COIN, false, "wallet-a")

    private class FakeService : InternalTransferService {
        var gate: CompletableDeferred<ExternalSendResult>? = null
        var result: ExternalSendResult = ExternalSendResult.Sent(null)
        val transfers = mutableListOf<InternalTransferRequest>()

        override fun observeBalance(balance: SendBalance): Flow<Dash?> = flowOf(null)
        override fun observeHasIdentity(): Flow<Boolean?> = flowOf(null)
        override fun observeShieldedEnabled(): Flow<Boolean?> = flowOf(null)
        override fun currentWalletIdOrNull(): String? = null
        override suspend fun estimateFee(from: SendBalance, to: SendBalance): FeeEstimate? = null
        override suspend fun maxSendable(from: SendBalance, to: SendBalance): Dash? = null
        override suspend fun transfer(
            from: SendBalance,
            to: SendBalance,
            amount: Dash,
            isMax: Boolean,
            expectedWalletIdHex: String?
        ): ExternalSendResult {
            transfers += InternalTransferRequest(from, to, amount, isMax, expectedWalletIdHex)
            return gate?.await() ?: result
        }
    }

    /** The persisted flag; reads, writes and clears can be gated or made to throw. */
    private class FakeMarker : ExternalSendInFlightMarker {
        var value = false
        var failReads = false
        var failWrites = false
        var readGate: CompletableDeferred<Unit>? = null
        var clearGate: CompletableDeferred<Unit>? = null
        var clearCalls = 0
        val log = mutableListOf<String>()

        override suspend fun isSet(): Boolean {
            readGate?.await()
            if (failReads) throw java.io.IOException("datastore")
            return value
        }

        override suspend fun set() {
            if (failWrites) throw java.io.IOException("datastore")
            log += "set"
            value = true
        }

        override suspend fun clear() {
            clearCalls++
            clearGate?.await()
            if (failWrites) throw java.io.IOException("datastore")
            log += "clear"
            value = false
        }
    }

    private val service = FakeService()
    private val marker = FakeMarker()

    private fun newExecutor() = InternalTransferExecutor(service, marker, CoroutineScope(UnconfinedTestDispatcher()))

    @Test
    fun `nothing can be submitted until the startup marker has been read`() {
        val readGate = CompletableDeferred<Unit>()
        marker.readGate = readGate
        val executor = newExecutor()
        assertEquals(InternalTransferExecution.Restoring, executor.state.value)
        assertFalse(executor.submit(request))
        assertTrue(service.transfers.isEmpty())

        readGate.complete(Unit)
        assertEquals(InternalTransferExecution.Idle, executor.state.value)
        assertTrue(executor.submit(request))
    }

    @Test
    fun `a set or unreadable startup marker restarts into may-have-gone-through`() {
        marker.value = true
        assertEquals(InternalTransferExecution.InterruptedByRestart, newExecutor().state.value)
        marker.value = false
        marker.failReads = true
        val executor = newExecutor()
        assertEquals(InternalTransferExecution.InterruptedByRestart, executor.state.value)
        assertFalse(executor.submit(request))

        marker.failReads = false
        executor.acknowledge()
        assertEquals(InternalTransferExecution.Idle, executor.state.value)
        assertFalse(marker.value)
    }

    @Test
    fun `the marker is written before the transfer, kept on Sent until acknowledged, cleared on Not sent`() {
        val gate = CompletableDeferred<ExternalSendResult>()
        service.gate = gate
        val executor = newExecutor()
        assertTrue(executor.submit(request))
        assertEquals(listOf(request), service.transfers)
        assertTrue("set before the service runs", marker.value)
        gate.complete(ExternalSendResult.Sent(null))
        assertTrue(marker.value)
        assertEquals(InternalTransferExecution.InterruptedByRestart, newExecutor().state.value)

        executor.acknowledge()
        assertFalse(marker.value)
        assertEquals(InternalTransferExecution.Idle, executor.state.value)

        service.gate = null
        service.result = ExternalSendResult.NotSent(NotSentReason.NOT_SYNCED)
        marker.log.clear()
        assertTrue(executor.submit(request))
        assertFalse(marker.value)
        assertEquals(listOf("set", "clear"), marker.log)
        executor.acknowledge()
        assertEquals(InternalTransferExecution.Idle, executor.state.value)
    }

    @Test
    fun `a marker that cannot be persisted sends nothing`() {
        marker.failWrites = true
        val executor = newExecutor()
        assertTrue(executor.submit(request))
        assertTrue(service.transfers.isEmpty())
        assertEquals(
            InternalTransferExecution.Finished(request, ExternalSendResult.NotSent(NotSentReason.GENERIC)),
            executor.state.value
        )
    }

    @Test
    fun `a second submission is refused while one runs or is unacknowledged`() {
        val gate = CompletableDeferred<ExternalSendResult>()
        service.gate = gate
        val executor = newExecutor()
        assertTrue(executor.submit(request))
        assertFalse(executor.submit(request))
        gate.complete(ExternalSendResult.MayHaveGoneThrough)
        assertFalse(executor.submit(request))
        assertEquals(1, service.transfers.size)
    }

    @Test
    fun `a double or stale acknowledgement cannot clear a later transfer's marker`() {
        val executor = newExecutor()
        executor.submit(request)
        val clearGate = CompletableDeferred<Unit>()
        marker.clearGate = clearGate

        executor.acknowledge()
        assertEquals(InternalTransferExecution.Acknowledging, executor.state.value)
        executor.acknowledge()
        assertEquals(1, marker.clearCalls)
        assertFalse(executor.submit(request))

        clearGate.complete(Unit)
        marker.clearGate = null
        assertEquals(InternalTransferExecution.Idle, executor.state.value)

        // A later transfer's marker survives a stale acknowledgement.
        val gate = CompletableDeferred<ExternalSendResult>()
        service.gate = gate
        assertTrue(executor.submit(request))
        executor.acknowledge()
        assertTrue(marker.value)
        assertEquals(1, marker.clearCalls)
        gate.complete(ExternalSendResult.MayHaveGoneThrough)
        assertTrue(marker.value)
    }
}
