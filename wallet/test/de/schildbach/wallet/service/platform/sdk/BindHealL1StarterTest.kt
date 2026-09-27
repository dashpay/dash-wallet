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
    fun aLaterArmReplacesAnEarlierOne() = runBlocking {
        val bound = MutableStateFlow(false)
        var starts = 0
        val starter = BindHealL1Starter(
            scope = scope,
            bindEstablished = bound,
            serviceTearingDown = { false },
            startL1 = { starts++; true }
        )
        starter.arm()
        starter.arm()
        bound.value = true
        assertEquals("two arms, one waiter, one start", 1, starts)
    }
}
