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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review, 2026-09-23: a startup bind that fails while the device is locked
 * makes the one-shot L1 start decline; the unlock receiver and the retry
 * ladder then only bind, and nothing started L1 for the rest of that service
 * lifetime. [BindHealL1Starter] is the piece that does, and these pin the
 * sequence the review asked for: startup failure, then a successful bind,
 * with no service recreation.
 *
 * Unconfined scope so a flow emission runs the waiter synchronously — the
 * same fixture discipline as [L1ShadowSyncServiceTest].
 */
class BindHealL1StarterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun startsL1Once_whenTheBindHealsInSession() = runBlocking {
        val bound = MutableStateFlow(false)
        var starts = 0
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )

        starter.arm() // the startup start declined: no wallet bound
        assertEquals("nothing to start while the bind is still failing", 0, starts)
        assertTrue(starter.isArmed)

        bound.value = true // the unlock receiver's retry succeeded
        assertEquals("the heal starts L1 exactly once", 1, starts)
        assertFalse("one heal, one start — the wait is over", starter.isArmed)

        bound.value = false
        bound.value = true // a later re-bind in the same lifetime is not this starter's job
        assertEquals(1, starts)
    }

    @Test
    fun doesNotStartL1_afterCancel_orWhileTheServiceIsTearingDown() = runBlocking {
        val bound = MutableStateFlow(false)
        var starts = 0
        var tearingDown = false
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { tearingDown },
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )

        // Cancelled by shutdown before the heal: nothing starts.
        starter.arm()
        starter.cancel()
        bound.value = true
        assertEquals(0, starts)
        assertFalse(starter.isArmed)

        // Armed, heal lands in the window where shutdown has begun: nothing starts.
        bound.value = false
        starter.arm()
        tearingDown = true
        bound.value = true
        assertEquals("a heal during teardown must not start an engine the service is stopping", 0, starts)
        assertFalse(starter.isArmed)
    }

    @Test
    fun doesNotStartL1_whenTheLifetimeThatArmedItHasEnded() = runBlocking {
        // CodeRabbit on #1584: the kick checks its service lifetime once, then
        // arms without suspending, so a shutdown can cancel the starter just
        // BEFORE the arm lands. That waiter outlives the teardown, and once the
        // cleanup flag clears a later bind heal would start L1 with no service.
        val bound = MutableStateFlow(false)
        var starts = 0
        var lifetime = 0L
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false }, // teardown already finished
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )

        val armedIn = lifetime
        starter.cancel() // the shutdown's cancel…
        lifetime++ // …and its lifetime bump
        starter.arm { lifetime == armedIn } // the kick's arm, landing late
        bound.value = true

        assertEquals("a heal after the arming lifetime ended starts nothing", 0, starts)
        assertFalse(starter.isArmed)
    }

    @Test
    fun anObsoleteArm_keepsTheCurrentLifetimesWaiter_andTheHealStartsL1() = runBlocking {
        // PastaClaw on #1632: an old startup kick passes its lifetime check, is
        // descheduled while shutdown and the next service start complete, then
        // reaches arm() after the new lifetime armed its own waiter. It used to
        // cancel that waiter and install one that refuses to start.
        val bound = MutableStateFlow(false)
        var starts = 0
        var lifetime = 0L
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )

        val oldLifetime = lifetime
        lifetime++ // shutdown of the old lifetime
        val newLifetime = lifetime
        starter.arm { lifetime == newLifetime } // the new lifetime's waiter
        starter.arm { lifetime == oldLifetime } // the old kick, landing late

        assertTrue("the current waiter is kept", starter.isArmed)
        bound.value = true
        assertEquals("the heal starts L1 for the running service", 1, starts)
    }

    @Test
    fun anObsoleteCancel_keepsTheCurrentLifetimesWaiter() = runBlocking {
        // The same race on the other branch: an old kick whose own start
        // succeeded cancels "its" waiter, which is now the new lifetime's.
        val bound = MutableStateFlow(false)
        var starts = 0
        var lifetime = 0L
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )

        val oldLifetime = lifetime
        lifetime++
        val newLifetime = lifetime
        starter.arm { lifetime == newLifetime }
        starter.cancel { lifetime == oldLifetime }

        assertTrue(starter.isArmed)
        bound.value = true
        assertEquals(1, starts)

        // Shutdown's own cancel stays unconditional.
        bound.value = false
        starter.arm { lifetime == newLifetime }
        starter.cancel()
        assertFalse(starter.isArmed)
    }

    @Test
    fun aLifetimeEndingWhileWaiting_startsNothingOnTheHeal() = runBlocking {
        val bound = MutableStateFlow(false)
        var starts = 0
        var lifetime = 0L
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )

        val armedIn = lifetime
        starter.arm { lifetime == armedIn }
        lifetime++ // that service shut down; its cancel did not reach the waiter
        bound.value = true

        assertEquals(0, starts)
        assertFalse(starter.isArmed)
    }

    @Test
    fun aShutdownBetweenTheChecksAndTheStart_keepsL1Down() = runBlocking {
        // PastaClaw / CodeRabbit on #1632: the lifetime and teardown checks are
        // not atomic with the start. A shutdown that passes them and finishes the
        // engine stop before the start runs used to be invisible to it: the
        // start sampled the stop generation itself, AFTER that stop, and
        // started L1 with no service. The fake engine refuses as
        // startIfEnabled(expectedExternalStopGeneration) does under its mutex.
        val bound = MutableStateFlow(false)
        var starts = 0
        var generation = 0L
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            // The last check passes, then the shutdown's engine stop completes.
            serviceTearingDown = { false.also { generation++ } },
            stopGeneration = { generation },
            startL1 = { expected -> (expected == generation).also { if (it) starts++ } }
        )

        starter.arm()
        bound.value = true

        assertEquals("a start decided before the stop does not outlive it", 0, starts)
        assertFalse(starter.isArmed)
    }

    @Test
    fun aStopBeforeTheHeal_doesNotBlockTheStart() = runBlocking {
        // The generation is sampled at the heal, not at arm(): a stop/start the
        // engine went through while the waiter waited (and which declined, the
        // bind still failing) must not leave the heal unable to start it.
        val bound = MutableStateFlow(false)
        var starts = 0
        var generation = 0L
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            stopGeneration = { generation },
            startL1 = { expected -> (expected == generation).also { if (it) starts++ } }
        )

        starter.arm()
        generation++ // a recovery path stopped the engine while the bind was failing
        bound.value = true

        assertEquals(1, starts)
    }

    @Test
    fun aLaterArmReplacesAnEarlierOne() = runBlocking {
        val bound = MutableStateFlow(false)
        var starts = 0
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            stopGeneration = { 0L },
            startL1 = { starts++; true }
        )
        starter.arm()
        starter.arm()
        bound.value = true
        assertEquals("two arms, one waiter, one start", 1, starts)
    }
}
