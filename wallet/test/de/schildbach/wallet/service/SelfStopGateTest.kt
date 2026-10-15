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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field crash (12.0.0-beta 39, Samsung A53, Android 16): the idle tick's bare
 * `stopSelf()` ran while the periodic alarm's `startForegroundService()` was
 * already queued, and the teardown with the foreground promise still open
 * crashed with ForegroundServiceDidNotStartInTimeException.
 *
 * [FakeSystem] models the system side of `stopSelfResult(id)`
 * (ActiveServices.stopServiceTokenLocked): it issues start ids, and honours a
 * stop only for the newest issued id; tearing down with a foreground start
 * still pending is the crash.
 */
class SelfStopGateTest {
    private class FakeSystem {
        var lastIssued = 0
        var foregroundPromisePending = false
        var crashed = false
        var stopped = false

        /** `startForegroundService()`: accepted, but not yet delivered to the service. */
        fun issueForegroundStart(): Int {
            foregroundPromisePending = true
            return ++lastIssued
        }

        fun stopSelfResult(startId: Int): Boolean {
            if (startId != lastIssued) return false
            stopped = true
            if (foregroundPromisePending) crashed = true
            return true
        }
    }

    @Test
    fun `a stop decided before a newer start is delivered uses the stale id and is refused`() {
        val system = FakeSystem()
        val gate = SelfStopGate()
        gate.onStart(system.issueForegroundStart())
        system.foregroundPromisePending = false

        val alarm = system.issueForegroundStart() // queued, not yet in onStartCommand
        assertFalse(gate.requestStop("idle", mustStop = false, stopSelfResult = system::stopSelfResult))
        assertFalse(system.stopped)
        assertFalse(system.crashed)

        // The alarm's onStartCommand runs, promotes, and a later idle tick stops cleanly.
        gate.onStart(alarm)
        system.foregroundPromisePending = false
        assertTrue(gate.requestStop("idle", mustStop = false, stopSelfResult = system::stopSelfResult))
        assertTrue(system.stopped)
        assertFalse(system.crashed)
    }

    @Test
    fun `a stop with no newer start is accepted`() {
        val system = FakeSystem()
        val gate = SelfStopGate()
        gate.onStart(system.issueForegroundStart())
        system.foregroundPromisePending = false

        assertEquals(1, gate.stopId)
        assertTrue(gate.requestStop("memory pressure", mustStop = false, stopSelfResult = system::stopSelfResult))
    }

    @Test
    fun `before any start is delivered the id is the never-started value, refused once a start is queued`() {
        val system = FakeSystem()
        val gate = SelfStopGate()
        assertEquals(SelfStopGate.NO_START_ID, gate.stopId)
        // An init failure in onCreate's coroutine, before the first onStartCommand.
        system.issueForegroundStart()
        assertFalse(
            gate.requestStop("initialization failed", mustStop = false, stopSelfResult = system::stopSelfResult)
        )
        assertFalse(system.crashed)
    }

    @Test
    fun `only a must-stop is owed to the newer command, and it is recorded before the attempt`() {
        val gate = SelfStopGate()
        gate.onStart(1)

        assertFalse(gate.requestStop("idle", mustStop = false) { false })
        assertNull("an ordinary stop is re-decided later, not owed", gate.owedStopReason)

        gate.requestStop("reset blockchain", mustStop = true) { startId ->
            // A newer command delivered between the write and the system's answer must see it.
            assertEquals("reset blockchain", gate.owedStopReason)
            assertEquals(1, startId)
            false
        }
        assertEquals("reset blockchain", gate.owedStopReason)
    }

    @Test
    fun `an accepted must-stop clears the debt`() {
        val gate = SelfStopGate()
        gate.onStart(1)
        assertFalse(gate.requestStop("wipe wallet", mustStop = true) { false })
        assertEquals("wipe wallet", gate.owedStopReason)

        gate.onStart(2)
        assertTrue(gate.requestStop("wipe wallet", mustStop = true) { it == 2 })
        assertNull("a bound service outlives the stop; its later commands must run", gate.owedStopReason)
    }

    @Test
    fun `a refusal reports the id actually used`() {
        val gate = SelfStopGate()
        gate.onStart(4)
        var reported = -1
        gate.requestStop("idle", mustStop = false, onRefused = { reported = it }) { startId ->
            gate.onStart(5) // a start delivered while the system was answering
            startId == 5
        }
        assertEquals(4, reported)
    }
}
