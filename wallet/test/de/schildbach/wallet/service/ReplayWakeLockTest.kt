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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    // An engine start or a durable save in flight, as BlockchainServiceImpl reads it live.
    private var otherHolds = false

    private val replayWakeLock = ReplayWakeLock(
        lock = { lock },
        sdkPath = { true },
        replayDue = ::replayDue,
        otherHolds = { otherHolds }
    )

    // The minute tick's call.
    private fun tick() = replayWakeLock.recheck()

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
        replayWakeLock.observeWedgeClears(scope, decider.standDownClears)
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
        replayWakeLock.observeWedgeClears(scope, decider.standDownClears)
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
    fun aDelayedObserver_thatMissesClearReWedgeClear_stillRetakesTheLock() = runTest {
        // Codex on #1632: observed as a StateFlow<Boolean>, a collector that is
        // slow to run sees clear → re-wedge → clear as the "not wedged" it had
        // already seen (StateFlow conflates), while the minute tick released the
        // lock during the re-wedge. Nothing re-took it. The collector here runs
        // only when the test scheduler is advanced, so the whole sequence happens
        // while it is held back.
        replayWakeLock.observeWedgeClears(backgroundScope, decider.standDownClears)
        runCurrent() // the observer is subscribed and idle
        tick()
        assertTrue("the replay holds the lock", lock.isHeld)

        exhaustTheWatchdog() // wedged
        decider.onFilterProgress(stuck + 1) // clear #1 — the observer does not run
        // The cursor moved one block and stalls there: re-wedged, budget spent.
        check(200 * 60_000L, height = stuck + 1)
        check(300 * 60_000L, height = stuck + 1)
        assertTrue(decider.standingDown)
        tick()
        assertFalse("the tick releases the lock during the re-wedge", lock.isHeld)
        decider.onFilterProgress(stuck + 2) // clear #2 — still not run
        assertFalse(decider.standingDown)
        assertFalse("nothing has re-checked yet", lock.isHeld)

        runCurrent() // the delayed observer finally runs
        assertTrue("the missed recovery still re-takes the lock", lock.isHeld)
        assertEquals(1, lock.count)
    }

    @Test
    fun aDelayedObserver_seeingAClearFollowedByANewWedge_acquiresNothing() = runTest {
        replayWakeLock.observeWedgeClears(backgroundScope, decider.standDownClears)
        runCurrent()
        exhaustTheWatchdog()
        tick()
        assertFalse(lock.isHeld)

        decider.onFilterProgress(stuck + 1) // clear — observer held back
        check(200 * 60_000L, height = stuck + 1)
        check(300 * 60_000L, height = stuck + 1) // re-wedged before it runs
        assertTrue(decider.standingDown)
        runCurrent()
        assertFalse("the clear it sees is already superseded by a wedge", lock.isHeld)
    }

    @Test
    fun aClearBetweenRegistrationAndTheObserversFirstRun_isNotTakenForTheBaseline() = runTest {
        exhaustTheWatchdog()
        tick()
        assertFalse(lock.isHeld)

        replayWakeLock.observeWedgeClears(backgroundScope, decider.standDownClears)
        decider.onFilterProgress(stuck + 1) // clears before the observer's first run
        runCurrent()
        assertTrue(lock.isHeld)
    }

    @Test
    fun aWedgeClearedWhileOffline_waitsForTheNetwork() {
        replayWakeLock.observeWedgeClears(scope, decider.standDownClears)
        exhaustTheWatchdog()
        networkImpeded = true
        tick()
        assertFalse(lock.isHeld)

        check(121 * 60_000L, height = stuck + 1)
        assertFalse("still offline: nothing to hold the CPU for", lock.isHeld)

        // The network back: the impediment update re-checks.
        networkImpeded = false
        replayWakeLock.recheck()
        assertTrue(lock.isHeld)
    }

    @Test
    fun aRecheckThatReadDueBeforeANetworkLoss_isUndoneByTheLossesOwnRecheck() {
        // PastaClaw on #1632: the re-acquire read "due" outside the lock's
        // monitor and acquired afterwards, and the network loss in between
        // could only ever re-acquire. The lock stayed held offline until a tick,
        // which does not come while the CPU sleeps. Now the read and the change
        // happen under one monitor, and the loss's own recheck, which runs after
        // the stale one, releases.
        exhaustTheWatchdog()
        tick()
        assertFalse(lock.isHeld)

        var lossLands = true
        val racing = ReplayWakeLock(
            lock = { lock },
            sdkPath = { true },
            replayDue = {
                replayDue().also {
                    // The network drops right after the recovery's read.
                    if (lossLands) {
                        lossLands = false
                        networkImpeded = true
                    }
                }
            },
            otherHolds = { otherHolds }
        )
        check(121 * 60_000L, height = stuck + 1) // the wedge clears
        racing.recheck() // the recovery: read "due", then the loss, then acquire
        assertTrue("the stale read still acquired", lock.isHeld)

        racing.recheck() // the impediment update of the loss
        assertFalse("offline: the replay does not keep the CPU", lock.isHeld)
        assertFalse(racing.held)
    }

    @Test
    fun aNetworkLoss_releasesAtOnce_unlessAnEngineStartOrSaveStillNeedsTheCpu() {
        tick()
        assertTrue(lock.isHeld)

        networkImpeded = true
        replayWakeLock.recheck()
        assertFalse("no tick needed to let the CPU sleep when offline", lock.isHeld)

        // An engine start or a durable save in flight is local work that goes
        // on offline: it holds the lock, and any recheck sees it.
        otherHolds = true
        replayWakeLock.recheck()
        assertTrue("the engine start still needs the CPU", lock.isHeld)
        otherHolds = false
        replayWakeLock.recheck()
        assertFalse(lock.isHeld)
    }

    @Test
    fun aNetworkLoss_afterTheDurableSaveCaughtUp_releasesWithoutATick() {
        // QA on #1632, D-1632-01: the tick recorded the durable-save hold as
        // true during a large replay, and the event recheck read that recorded
        // value. A network loss after the save had caught up then kept the lock
        // until the next tick: 53 s and 11 s on the emulator.
        otherHolds = true // the durable save is behind
        tick()
        assertTrue(lock.isHeld)

        otherHolds = false // it caught up — no tick has run since
        networkImpeded = true
        replayWakeLock.recheck() // the loss's impediment update
        assertFalse("released by the network event, not the next tick", lock.isHeld)
        assertFalse(replayWakeLock.held)
    }

    @Test
    fun aWedgeClearingAfterTeardownBegan_acquiresNothing() {
        replayWakeLock.observeWedgeClears(scope, decider.standDownClears)
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
