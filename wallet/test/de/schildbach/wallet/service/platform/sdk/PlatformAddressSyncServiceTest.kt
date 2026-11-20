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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start/stop fencing and forced passes of [PlatformAddressSyncService] over a fake SDK seam. */
@OptIn(ExperimentalCoroutinesApi::class)
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
        override suspend fun isRunning(): Boolean {
            runningFailure?.let { throw it }
            return running
        }
        /** Starts that fail before [startFailure] is consulted (a transient native failure). */
        var startFailuresRemaining = 0

        /** Virtual times ([clock]) of the successful starts: with a running loop, the forced passes. */
        val startedAt = mutableListOf<Long>()
        var clock: () -> Long = { 0L }
        var runningFailure: Exception? = null

        /** Parks a stop (after it stopped the loop) until completed: the stop→start gap. */
        var afterStopGate: CompletableDeferred<Unit>? = null
        val stopEntered = CompletableDeferred<Unit>()

        override suspend fun start() {
            startEntered.complete(Unit)
            startGate?.await()
            if (startFailuresRemaining > 0) {
                startFailuresRemaining--
                throw IllegalStateException("native start failed")
            }
            startFailure?.let { throw it }
            starts++
            startedAt += clock()
            running = true
        }
        override suspend fun stop() {
            stopFailure?.let { throw it }
            stops++
            running = false
            stopEntered.complete(Unit)
            afterStopGate?.await()
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

    // ── syncNow / schedulePlatformResync (iOS schedulePlatformResync) ──

    @Test
    fun syncNow_forcesAPass_byRestartingARunningLoop() = runBlocking {
        source.running = true
        val service = PlatformAddressSyncService(source)

        assertTrue(service.syncNow())
        assertEquals(1, source.stops)
        assertEquals(1, source.starts)
        assertTrue(source.running)
    }

    /**
     * A newer schedulePlatformResync cancels the older job; landing between the restart's stop
     * and start, that cancellation must not leave the loop stopped (every later syncNow would
     * then be a no-op at "not running").
     */
    @Test
    fun syncNow_cancelledBetweenStopAndStart_stillRestartsTheLoop() = runBlocking {
        source.running = true
        source.afterStopGate = CompletableDeferred()
        val service = PlatformAddressSyncService(source)

        val call = async(Dispatchers.Default) { service.syncNow() }
        withTimeout(5_000) { source.stopEntered.await() }
        call.cancel()
        source.afterStopGate?.complete(Unit)
        call.join()

        assertEquals(1, source.starts)
        assertTrue("the loop runs again", source.running)
        source.afterStopGate = null
        assertTrue("a later forced pass still works", service.syncNow())
    }

    @Test
    fun syncNow_aFailedStart_isRetriedOnce() = runBlocking {
        source.running = true
        source.startFailuresRemaining = 1
        val service = PlatformAddressSyncService(source)

        assertTrue(service.syncNow())
        assertEquals(1, source.starts)
        assertTrue(source.running)
    }

    @Test
    fun syncNow_twoFailedStarts_giveUp_contained() = runBlocking {
        source.running = true
        source.startFailuresRemaining = 2
        val service = PlatformAddressSyncService(source)

        assertFalse(service.syncNow())
        assertEquals(0, source.startFailuresRemaining)
        assertFalse(source.running)
    }

    /** A stop that arrives during the restart still wins: the restarted loop is stopped again. */
    @Test
    fun syncNow_aStopDuringTheRestart_wins() = runBlocking {
        source.running = true
        source.afterStopGate = CompletableDeferred()
        val service = PlatformAddressSyncService(source, stopLockTimeoutMs = 50)

        val call = async(Dispatchers.Default) { service.syncNow() }
        withTimeout(5_000) { source.stopEntered.await() }
        service.stop() // times out on the lock the restart holds; bumps the generation
        source.afterStopGate?.complete(Unit)

        assertFalse(call.await())
        assertFalse(source.running)
    }

    @Test
    fun syncNow_isANoOp_whenTheLoopIsNotRunning_andNeverStartsIt() = runBlocking {
        val service = PlatformAddressSyncService(source)

        assertFalse(service.syncNow())
        assertEquals(0, source.startedAt.size)
        assertEquals(0, source.starts)
    }

    @Test
    fun syncNow_isANoOp_whenNotReady() = runBlocking {
        source.running = true
        source.ready = { false }
        val service = PlatformAddressSyncService(source)

        assertFalse(service.syncNow())
        assertEquals(0, source.startedAt.size)
    }

    @Test
    fun syncNow_failsClosed_onUnreadableState_andContainsFailures() = runBlocking {
        source.running = true
        val service = PlatformAddressSyncService(source)

        source.ready = { throw IllegalStateException("cutover state unreadable") }
        assertFalse(service.syncNow())
        source.ready = { true }
        source.runningFailure = IllegalStateException("manager closed")
        assertFalse(service.syncNow())
        source.runningFailure = null
        source.stopFailure = IllegalStateException("native stop failed")
        assertFalse(service.syncNow())
        assertEquals(0, source.startedAt.size)
    }

    private fun TestScope.resyncService() = PlatformAddressSyncService(
        source,
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler)
    ).also { source.clock = { testScheduler.currentTime } }

    @Test
    fun schedulePlatformResync_forcesPassesAt0_4And12Seconds() = runTest {
        source.running = true
        val service = resyncService()

        service.schedulePlatformResync()
        runCurrent()
        assertEquals(listOf(0L), source.startedAt)
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(listOf(0L), source.startedAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 4_000L), source.startedAt)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf(0L, 4_000L, 12_000L), source.startedAt)
        assertEquals(PlatformAddressSyncService.RESYNC_OFFSETS_MS, source.startedAt)
    }

    @Test
    fun schedulePlatformResync_aNewerScheduleReplacesTheOlder() = runTest {
        source.running = true
        val service = resyncService()

        service.schedulePlatformResync()
        runCurrent()
        advanceTimeBy(2_000)
        service.schedulePlatformResync()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        // old: 0; new: 2000, 6000, 14000 — the old one's 4 s and 12 s never fire
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L), source.startedAt)
    }

    @Test
    fun schedulePlatformResync_isCancelledByStop() = runTest {
        source.running = true
        val service = resyncService()

        service.schedulePlatformResync()
        runCurrent()
        service.stop() // engine teardown, and a wallet wipe through stopSdkEngines
        source.running = true // even a loop someone restarted is not forced by the old schedule
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(0L), source.startedAt)
    }

    @Test
    fun schedulePlatformResync_withoutAScope_schedulesNothing() = runBlocking {
        source.running = true
        PlatformAddressSyncService(source).schedulePlatformResync()
        assertEquals(0, source.startedAt.size)
    }
}
