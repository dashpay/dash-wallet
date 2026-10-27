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

import android.app.Application
import android.app.Notification
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.HandlerThread
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.ReplayRestartAlarmAction
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.decideOnReplayRestartAlarm
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Job
import org.dash.wallet.common.data.entity.BlockchainState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The service's own stops go through `stopSelfResult` with the newest
 * delivered start id, never a bare `stopSelf()` (see [SelfStopGate] for the
 * field crash).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ServiceSelfStopTest {
    private val service = spyk(BlockchainServiceImpl())
    private val application = mockk<WalletApplication>(relaxed = true)

    /** The system's answer: accept only the newest issued id. */
    private var lastIssuedStartId = 0

    init {
        service.application = application
        every { service.stopSelfResult(any()) } answers { firstArg<Int>() == lastIssuedStartId }
        every { service.stopSelf() } answers { error("a bare stopSelf() ignores queued starts") }
    }

    @After
    fun tearDown() {
        ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
        ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
    }

    private fun deliverStart() {
        service.recordStartCommand(++lastIssuedStartId)
    }

    @Test
    fun `the idle verdict stops with the newest delivered start id and is re-decided after a refusal`() {
        deliverStart()
        deliverStart()
        // The alarm's start is issued but not yet delivered when the tick decides.
        lastIssuedStartId++
        service.onIdleVerdict()
        verify(exactly = 1) { service.stopSelfResult(2) }

        // Once it is delivered, the next (still idle) tick stops the service.
        service.recordStartCommand(lastIssuedStartId)
        service.onIdleVerdict()
        verify(exactly = 1) { service.stopSelfResult(3) }
        verify(exactly = 0) { service.stopSelf() }
    }

    @Test
    fun `memory pressure stops with the newest delivered start id`() {
        stubRestartAlarm() // an accepted memory-pressure stop arms its recovery restart
        deliverStart()
        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        verify(exactly = 1) { service.stopSelfResult(1) }
        verify(exactly = 0) { service.stopSelf() }
    }

    @Test
    fun `a refused wipe stop is owed to the newer command, which stops instead of running`() {
        ReflectionHelpers.setField(service, "initCompleted", true)
        every { application.wallet } returns mockk()
        every { application.isWalletLoadDegraded } returns false
        every { application.isRecoveryResetPending } returns false
        deliverStart()
        service.recordWipeRequest(BlockchainService.ACTION_WIPE_WALLET)
        lastIssuedStartId++ // an alarm start is queued behind the wipe

        assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_WIPE_WALLET))
        verify(exactly = 1) { service.stopSelfResult(1) }

        // The alarm's command: a normal start, but the wipe's stop is owed.
        service.recordStartCommand(lastIssuedStartId)
        assertTrue(service.handleWalletLifecycleCommand(null))
        verify(exactly = 1) { service.stopSelfResult(2) }
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "deleteWalletFileOnShutdown"))
        verify(exactly = 0) { service.stopSelf() }
    }

    @Test
    fun `a refused ordinary stop leaves the newer command free to run`() {
        ReflectionHelpers.setField(service, "initCompleted", true)
        every { application.wallet } returns mockk()
        every { application.isWalletLoadDegraded } returns false
        every { application.isRecoveryResetPending } returns false
        deliverStart()
        lastIssuedStartId++

        assertFalse(service.stopSelfIfNoNewerStart("idle"))
        service.recordStartCommand(lastIssuedStartId)
        assertFalse(service.handleWalletLifecycleCommand(null))
    }

    @Test
    fun `an accepted wipe or reset stop on a bound service leaves later commands running`() {
        ReflectionHelpers.setField(service, "initCompleted", true)
        every { application.wallet } returns mockk()
        every { application.isWalletLoadDegraded } returns false
        every { application.isRecoveryResetPending } returns false
        deliverStart()

        // Accepted, but MainActivity's binding keeps the instance alive.
        assertTrue(service.stopSelfIfNoNewerStart("reset blockchain", mustStop = true))
        verify(exactly = 1) { service.stopSelfResult(1) }

        deliverStart()
        assertFalse(service.handleWalletLifecycleCommand(null))
        verify(exactly = 0) { service.stopSelfResult(2) }
    }

    @Test
    fun `a stop landing between delivery and foreground promotion uses the previous id and stays owed`() {
        deliverStart()
        val gate = ReflectionHelpers.getField<SelfStopGate>(service, "selfStopGate")
        // The alarm's startForegroundService(): issued, its promise armed.
        val alarm = ++lastIssuedStartId
        val intent =
            Intent(BlockchainService.ACTION_RESET_BLOCKCHAIN)
                .putExtra(BlockchainServiceImpl.START_AS_FOREGROUND_EXTRA, true)
        val promotedWith = mutableListOf<Int>()
        every { service["createNetworkSyncNotification"](any<BlockchainState>()) } returns mockk<Notification>()
        every { service["startForegroundAndCatch"](any<Notification>()) } answers {
            // An off-Main stop (e.g. a reset's) landing while onStartCommand
            // has the start but has not promoted yet: it must not see the
            // alarm's id, or the system would accept it with the promise open.
            promotedWith += gate.stopId
            assertFalse(service.stopSelfIfNoNewerStart("reset blockchain", mustStop = true))
        }

        service.onStartCommand(intent, 0, alarm)

        assertEquals("the id is published only after the promotion", listOf(1), promotedWith)
        verify(exactly = 1) { service.stopSelfResult(1) }
        verify(exactly = 0) { service.stopSelfResult(alarm) }
        assertEquals(alarm, gate.stopId)

        // The alarm's command then honours the owed stop instead of running.
        assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_RESET_BLOCKCHAIN))
        verify(exactly = 1) { service.stopSelfResult(alarm) }
        verify(exactly = 0) { service.stopSelf() }
    }

    // ── recovery restarts through the gate (#1623 merged with the round-1 recovery alarm) ──

    /** Restart alarms this instance armed; counted instead of reaching the AlarmManager. */
    private var armedRestarts = 0

    private fun stubRestartAlarm() {
        every { service["rescheduleService"]() } answers { armedRestarts++; Unit }
    }

    private fun recoveryRequested(): Boolean = ReflectionHelpers.getField(service, "recoveryRestartRequested")

    private fun initialisedWithWallet() {
        ReflectionHelpers.setField(service, "initCompleted", true)
        every { application.wallet } returns mockk()
        every { application.isWalletLoadDegraded } returns false
        every { application.isRecoveryResetPending } returns false
    }

    /** What this instance's onDestroy would do with the alarm, given what it has recorded. */
    private fun teardownDecision(replaying: Boolean = false) =
        decideOnReplayRestartAlarm(
            initialised = ReflectionHelpers.getField(service, "initCompleted"),
            replaying = replaying,
            deliberateStop = ReflectionHelpers.getField<Boolean>(service, "deleteWalletFileOnShutdown") ||
                ReflectionHelpers.getField<Boolean>(service, "resetBlockchainOnShutdown"),
            bindBlocked = false,
            recoveryRestartRequested = recoveryRequested()
        )

    @Test
    fun `a memory-pressure stop the gate refuses arms no recovery alarm and sets no flag`() {
        stubRestartAlarm()
        deliverStart()
        lastIssuedStartId++ // a newer start is pending: the stop is refused

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        verify(exactly = 1) { service.stopSelfResult(1) }
        assertEquals("a refused stop must not leave a stray alarm", 0, armedRestarts)
        assertFalse("a refused stop must not leave a stale recovery flag", recoveryRequested())
    }

    @Test
    fun `an accepted memory-pressure stop keeps its recovery alarm through teardown`() {
        stubRestartAlarm()
        initialisedWithWallet()
        deliverStart()

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        verify(exactly = 1) { service.stopSelfResult(1) }
        assertEquals(1, armedRestarts)
        assertTrue(recoveryRequested())
        // no replay in progress, yet the teardown leaves the alarm that is to bring it back
        assertEquals(ReplayRestartAlarmAction.LEAVE, teardownDecision())
    }

    @Test
    fun `a refused must-stop recovery arms its alarm when the owed stop is honoured`() {
        // the blockstore stall's shape: one-shot, so the stop is owed when refused
        stubRestartAlarm()
        initialisedWithWallet()
        deliverStart()
        lastIssuedStartId++

        assertFalse(service.stopSelfForRecovery("blockstore memory stall", mustStop = true))
        assertEquals(0, armedRestarts)
        assertFalse(recoveryRequested())

        // the newer command honours the owed stop, and that stop is the recovery stop
        service.recordStartCommand(lastIssuedStartId)
        assertTrue(service.handleWalletLifecycleCommand(null))
        verify(exactly = 1) { service.stopSelfResult(2) }
        assertEquals(1, armedRestarts)
        assertTrue(recoveryRequested())
        assertEquals(ReplayRestartAlarmAction.LEAVE, teardownDecision())
    }

    @Test
    fun `a recovery restart that reaches a still-running instance clears the flag`() {
        // A bound service outlives its accepted stop; once a later command runs normally the
        // recovery has happened, and the next teardown must decide afresh.
        stubRestartAlarm()
        initialisedWithWallet()
        deliverStart()
        assertTrue(service.stopSelfForRecovery("memory pressure"))
        assertTrue(recoveryRequested())

        deliverStart()
        assertFalse(service.handleWalletLifecycleCommand(null))

        assertFalse(recoveryRequested())
        assertEquals(ReplayRestartAlarmAction.RETIRE, teardownDecision())
    }

    @Test
    fun `a wipe still retires the alarm after a recovery stop`() {
        stubRestartAlarm()
        initialisedWithWallet()
        deliverStart()
        lastIssuedStartId++
        assertFalse(service.stopSelfForRecovery("blockstore memory stall", mustStop = true))

        // the newer command is a wipe; the owed recovery stop is honoured in its place
        service.recordStartCommand(lastIssuedStartId)
        service.recordWipeRequest(BlockchainService.ACTION_WIPE_WALLET)
        assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_WIPE_WALLET))

        assertEquals(ReplayRestartAlarmAction.RETIRE, teardownDecision())
    }

    // ── the stuck-cleanup recovery stop (review ad532a825b07) ──

    @Test
    fun `a refused stuck-cleanup stop still brings the service back through the newer command`() {
        // initCompleted stays false: this instance never initialises
        stubRestartAlarm()
        every { application.wallet } returns mockk()
        deliverStart()
        service.releaseInitLatchForStuckCleanup()
        lastIssuedStartId++ // a newer start is queued

        assertFalse(service.stopForStuckCleanup())
        assertEquals(0, armedRestarts)

        // the newer command: whichever branch stops it must arm the recovery restart
        service.recordStartCommand(lastIssuedStartId)
        assertTrue(service.handleWalletLifecycleCommand(null))
        verify(exactly = 1) { service.stopSelfResult(2) }
        assertEquals("the accepted stop arms the recovery restart", 1, armedRestarts)
        assertTrue(recoveryRequested())
        assertEquals(ReplayRestartAlarmAction.LEAVE, teardownDecision())
    }

    @Test
    fun `a command released by the stuck-cleanup latch before the recovery stop runs still arms it`() {
        // The ordering window: the latch is released first, and a queued command reaches
        // handleWalletLifecycleCommand before the recovery stop has run.
        stubRestartAlarm()
        every { application.wallet } returns mockk()
        deliverStart()

        service.releaseInitLatchForStuckCleanup()
        assertTrue(service.handleWalletLifecycleCommand(null))
        verify(exactly = 1) { service.stopSelfResult(1) }
        assertEquals("the stop that landed is the recovery stop", 1, armedRestarts)
        assertTrue(recoveryRequested())
    }
}
