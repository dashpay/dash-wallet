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
package de.schildbach.wallet.service.platform.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start/stop fencing of [PlatformAddressSyncService] over a fake SDK seam. */
class PlatformAddressSyncServiceTest {

    private class FakeSource : PlatformAddressSyncSource {
        var ready: () -> Boolean = { true }
        var running = false
        var starts = 0
        var stops = 0
        var startGate: CompletableDeferred<Unit>? = null
        val startEntered = CompletableDeferred<Unit>()
        var startFailure: Exception? = null
        var stopFailure: Exception? = null

        override suspend fun ready() = ready.invoke()
        override suspend fun isRunning() = running
        override suspend fun start() {
            startEntered.complete(Unit)
            startGate?.await()
            startFailure?.let { throw it }
            starts++
            running = true
        }
        override suspend fun stop() {
            stopFailure?.let { throw it }
            stops++
            running = false
        }
    }

    private val source = FakeSource()

    @Test
    fun startsWhenReady() = runBlocking {
        val service = PlatformAddressSyncService(source)

        assertTrue(service.startIfReady(service.stopGeneration()))
        assertEquals(1, source.starts)
    }

    @Test
    fun doesNotStartBeforeTheWalletIsBound() = runBlocking {
        source.ready = { false }
        val service = PlatformAddressSyncService(source)

        assertFalse(service.startIfReady(service.stopGeneration()))
        assertEquals(0, source.starts)
    }

    @Test
    fun anUnreadableReadinessDoesNotStart() = runBlocking {
        source.ready = { throw IllegalStateException("cutover state unreadable") }
        val service = PlatformAddressSyncService(source)

        assertFalse(service.startIfReady(service.stopGeneration()))
        assertEquals(0, source.starts)
    }

    @Test
    fun isIdempotentWhileRunning() = runBlocking {
        source.running = true
        val service = PlatformAddressSyncService(source)

        assertTrue(service.startIfReady(service.stopGeneration()))
        assertEquals(0, source.starts)
    }

    @Test
    fun aStopAfterTheStartWasDecided_refusesIt() = runBlocking {
        val service = PlatformAddressSyncService(source)
        val decidedUnder = service.stopGeneration()

        service.stop()

        assertFalse(service.startIfReady(decidedUnder))
        assertEquals(0, source.starts)
        assertFalse(source.running)
    }

    @Test
    fun aStopThatWaitsForAnInFlightStart_stopsTheLoopItStarted() = runBlocking {
        source.startGate = CompletableDeferred()
        val service = PlatformAddressSyncService(source)

        val start = async(Dispatchers.Default) { service.startIfReady(service.stopGeneration()) }
        withTimeout(5_000) { source.startEntered.await() }
        val stop = async(Dispatchers.Default) { service.stop() }
        source.startGate?.complete(Unit)
        start.await()
        stop.await()

        assertFalse(source.running)
    }

    @Test
    fun aStopThatTimedOutOnTheLock_isPaidByTheStartWhenItReturns() = runBlocking {
        source.startGate = CompletableDeferred()
        val service = PlatformAddressSyncService(source, stopLockTimeoutMs = 50)

        val start = async(Dispatchers.Default) { service.startIfReady(service.stopGeneration()) }
        withTimeout(5_000) { source.startEntered.await() }
        service.stop() // times out on the lock held by the start
        assertEquals(0, source.stops)
        source.startGate?.complete(Unit)

        assertFalse(start.await())
        assertEquals(1, source.starts)
        assertEquals(1, source.stops)
        assertFalse(source.running)
    }

    @Test
    fun startAndStopFailuresAreContained() = runBlocking {
        source.startFailure = IllegalStateException("native start failed")
        source.stopFailure = IllegalStateException("native stop failed")
        val service = PlatformAddressSyncService(source)

        assertFalse(service.startIfReady(service.stopGeneration()))
        service.stop()
    }

    @Test
    fun aStartAfterAStopIsAllowedUnderTheNewGeneration() = runBlocking {
        val service = PlatformAddressSyncService(source)
        service.stop()

        assertTrue(service.startIfReady(service.stopGeneration()))
        assertEquals(1, source.starts)
    }
}
