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
import android.content.ComponentCallbacks2
import android.os.HandlerThread
import de.schildbach.wallet.WalletApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Job
import org.junit.After
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
}
