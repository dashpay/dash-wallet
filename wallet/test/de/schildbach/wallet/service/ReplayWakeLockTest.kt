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

package de.schildbach.wallet.service

import de.schildbach.wallet.service.platform.sdk.FilterStallWatchdogDecider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replay's own hold on the service wake lock ([ReplayWakeLock]), wired to
 * the real filter-stall watchdog decider.
 *
 * Review on #1632 (PastaClaw, CodeRabbit): once the watchdog had spent its
 * restarts the minute tick released the lock, and only the tick or a network
 * change could take it back. A replay that recovered on its own (the cursor
 * moved) ran without the lock, and ACTION_TIME_TICK is not delivered while
 * the CPU sleeps, so a screen-off replay stayed asleep until something
 * unrelated woke the device.
 *
 * Unconfined scope so a flow emission runs the observer synchronously.
 */
class ReplayWakeLockTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class FakeLock : ReplayWakeLockHandle {
        var count = 0
        var acquisitions = 0
        override val isHeld: Boolean get() = count > 0
        override fun acquire() {
            count++
            acquisitions++
        }
        override fun release() {
            count--
        }
    }

    private val lock = FakeLock()
    private val decider = FilterStallWatchdogDecider(stallThresholdMs = 10 * 60_000L, maxRestarts = 3)
    private var networkImpeded = false

    // Mirrors BlockchainServiceImpl.replayWakeLockDue() with a replay in progress.
    private fun replayDue() = replayHoldsWakeLock(
        replaying = true,
        networkImpeded = networkImpeded,
        filterScanWedged = decider.standingDown
    )

    private val replayWakeLock = ReplayWakeLock(
        lock = { lock },
        sdkPath = { true },
        replayDue = ::replayDue
    )

    // The minute tick's call, with no engine start or durable save in flight.
    private fun tick() = replayWakeLock.hold(replayDue())

    private val stuck = 1_500_000L // more than one commit batch behind
    private val target = 1_556_896L

    private fun check(atMs: Long, height: Long = stuck) =
        decider.onCheck(atMs, height, target, lastWalletEventMs = 0L, walletSyncedHeight = stuck)

    /** The restart ladder of 10 / 20 / 30 minutes, then the stand-down. */
    private fun exhaustTheWatchdog() {
        check(0L)
        assertEquals(FilterStallWatchdogDecider.Decision.RESTART, check(10 * 60_000L + 1))
        assertEquals(FilterStallWatchdogDecider.Decision.RESTART, check(30 * 60_000L + 2))
        assertEquals(FilterStallWatchdogDecider.Decision.RESTART, check(60 * 60_000L + 3))
        assertEquals(FilterStallWatchdogDecider.Decision.EXHAUSTED, check(120 * 60_000L))
        assertTrue(decider.standingDown)
    }

    @Test
    fun aRecoveryFromAnExhaustedWedge_retakesTheLock_withoutATickOrANetworkChange() {
        replayWakeLock.observeWedge(scope, decider.standingDownState)
        tick()
        assertTrue("the replay holds the lock", lock.isHeld)

        exhaustTheWatchdog()
        tick()
        assertFalse("a wedge the watchdog gave up on does not keep the CPU awake", lock.isHeld)
        assertFalse(replayWakeLock.held)

        // The scan recovers on its own: the cursor moves. No tick, no network event.
        check(121 * 60_000L, height = stuck + 1)
        assertFalse(decider.standingDown)
        assertTrue("the first sign of life gives the lock back to the replay", lock.isHeld)
        assertEquals(2, lock.acquisitions)
        assertTrue(replayWakeLock.held)

        // The next tick agrees; it neither double-acquires nor releases.
        tick()
        assertEquals(1, lock.count)
    }

    @Test
    fun theEngineMovingRetakesTheLock_beforeTheWatchdogsNextCheck() {
        // The watchdog's own check runs on a one-minute delay that only advances
        // while awake; the progress feed and the event tap are what see the
        // engine move during a brief wake.
        replayWakeLock.observeWedge(scope, decider.standingDownState)
        exhaustTheWatchdog()
        tick()
        assertFalse(lock.isHeld)

        decider.onFilterProgress(stuck)
        assertFalse("the same cursor is still the wedge", lock.isHeld)
        decider.onFilterProgress(0L)
        assertFalse("a snapshot with no filter position is not movement", lock.isHeld)
        decider.onFilterProgress(stuck + 1)
        assertTrue("the cursor moved: the lock is back", lock.isHeld)

        // A later stand-down, ended by a wallet event instead.
        check(200 * 60_000L, height = stuck + 1)
        check(300 * 60_000L, height = stuck + 1)
        assertTrue(decider.standingDown)
        tick()
        assertFalse(lock.isHeld)
        decider.onWalletEvent()
        assertTrue(lock.isHeld)
    }

    @Test
    fun aWedgeClearedWhileOffline_waitsForTheNetwork() {
        replayWakeLock.observeWedge(scope, decider.standingDownState)
        exhaustTheWatchdog()
        networkImpeded = true
        tick()
        assertFalse(lock.isHeld)

        check(121 * 60_000L, height = stuck + 1)
        assertFalse("still offline: nothing to hold the CPU for", lock.isHeld)

        // The network back: the impediment update re-checks.
        networkImpeded = false
        replayWakeLock.reacquireIfDue()
        assertTrue(lock.isHeld)
    }

    @Test
    fun aWedgeClearingAfterTeardownBegan_acquiresNothing() {
        replayWakeLock.observeWedge(scope, decider.standingDownState)
        tick()
        exhaustTheWatchdog()
        tick()
        assertFalse(lock.isHeld)

        replayWakeLock.retire()
        // The engine stop of the teardown clears the wedge.
        decider.onEngineStopped()
        assertFalse("no acquisition after onDestroy began", lock.isHeld)
        assertEquals(1, lock.acquisitions)
    }

    @Test
    fun teardownReleasesOnlyTheReplaysOwnCount() {
        lock.acquire() // the dashj path's own acquisition
        tick()
        assertEquals(2, lock.count)

        replayWakeLock.retire()
        replayWakeLock.releaseForTeardown()
        assertEquals(1, lock.count)
        assertFalse(replayWakeLock.held)
        replayWakeLock.releaseForTeardown()
        assertEquals("idempotent", 1, lock.count)
    }
}
