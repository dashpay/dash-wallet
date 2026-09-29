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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-JVM tests for the pure Phase 5d cutover state machine. */
class CutoverStateMachineTest {

    private fun next(current: CutoverState, action: CutoverAction, ready: Boolean) =
        nextCutoverState(current, action, ready)

    @Test
    fun observeReadiness_advisoryEdge_bothDirections() {
        assertEquals(
            CutoverState.READY_OBSERVED,
            next(CutoverState.DUAL_RUNNING, CutoverAction.OBSERVE_READINESS, ready = true)
        )
        // Not ready: no advance.
        assertEquals(
            CutoverState.DUAL_RUNNING,
            next(CutoverState.DUAL_RUNNING, CutoverAction.OBSERVE_READINESS, ready = false)
        )
        // Readiness lost after observation: revert.
        assertEquals(
            CutoverState.DUAL_RUNNING,
            next(CutoverState.READY_OBSERVED, CutoverAction.OBSERVE_READINESS, ready = false)
        )
        // Still ready: stay.
        assertEquals(
            CutoverState.READY_OBSERVED,
            next(CutoverState.READY_OBSERVED, CutoverAction.OBSERVE_READINESS, ready = true)
        )
    }

    @Test
    fun commit_requiresReadyObservedAndReady() {
        assertEquals(
            CutoverState.CUT_OVER,
            next(CutoverState.READY_OBSERVED, CutoverAction.COMMIT_CUTOVER, ready = true)
        )
        // The race guard: readiness lost between observe and commit -> no flip.
        assertEquals(
            CutoverState.READY_OBSERVED,
            next(CutoverState.READY_OBSERVED, CutoverAction.COMMIT_CUTOVER, ready = false)
        )
        // Cannot skip the READY_OBSERVED gate straight from DUAL_RUNNING.
        assertEquals(
            CutoverState.DUAL_RUNNING,
            next(CutoverState.DUAL_RUNNING, CutoverAction.COMMIT_CUTOVER, ready = true)
        )
    }

    @Test
    fun rollback_onlyFromCutOver_notFromSettled() {
        assertEquals(
            CutoverState.DUAL_RUNNING,
            next(CutoverState.CUT_OVER, CutoverAction.ROLLBACK, ready = false)
        )
        // SETTLED is final — the horizon passed.
        assertEquals(
            CutoverState.SETTLED,
            next(CutoverState.SETTLED, CutoverAction.ROLLBACK, ready = false)
        )
    }

    @Test
    fun settle_onlyFromCutOver() {
        assertEquals(
            CutoverState.SETTLED,
            next(CutoverState.CUT_OVER, CutoverAction.SETTLE, ready = false)
        )
        assertEquals(
            CutoverState.DUAL_RUNNING,
            next(CutoverState.DUAL_RUNNING, CutoverAction.SETTLE, ready = true)
        )
    }

    @Test
    fun readinessNeverMovesAFlippedInstall() {
        // Post-flip, parity-vs-dashj is meaningless; observe must not regress.
        assertEquals(
            CutoverState.CUT_OVER,
            next(CutoverState.CUT_OVER, CutoverAction.OBSERVE_READINESS, ready = false)
        )
        assertEquals(
            CutoverState.SETTLED,
            next(CutoverState.SETTLED, CutoverAction.OBSERVE_READINESS, ready = false)
        )
    }

    @Test
    fun dashjEngineGate_falseOnlyWhenFlipped() {
        assertTrue(dashjEngineMayStart(CutoverState.DUAL_RUNNING))
        assertTrue(dashjEngineMayStart(CutoverState.READY_OBSERVED))
        assertFalse(dashjEngineMayStart(CutoverState.CUT_OVER))
        assertFalse(dashjEngineMayStart(CutoverState.SETTLED))
    }

    @Test
    fun fromStored_defaultsToDualRunning() {
        assertEquals(CutoverState.DUAL_RUNNING, CutoverState.fromStored(null))
        assertEquals(CutoverState.DUAL_RUNNING, CutoverState.fromStored("GARBAGE"))
        assertEquals(CutoverState.CUT_OVER, CutoverState.fromStored("CUT_OVER"))
    }

    @Test
    fun fullHappyPath_dualToSettled() {
        var s = CutoverState.DUAL_RUNNING
        s = next(s, CutoverAction.OBSERVE_READINESS, ready = true); assertEquals(CutoverState.READY_OBSERVED, s)
        s = next(s, CutoverAction.COMMIT_CUTOVER, ready = true); assertEquals(CutoverState.CUT_OVER, s)
        s = next(s, CutoverAction.SETTLE, ready = false); assertEquals(CutoverState.SETTLED, s)
    }
}
