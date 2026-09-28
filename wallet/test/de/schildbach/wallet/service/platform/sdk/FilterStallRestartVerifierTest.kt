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

import de.schildbach.wallet.service.platform.sdk.FilterStallRestartVerifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA D-901b: a filter-stall restart is judged by whether the cursor passes the stuck height. */
class FilterStallRestartVerifierTest {

    @Test
    fun nothingArmed_noVerdicts() {
        val v = FilterStallRestartVerifier()
        assertNull(v.onProgress(1_000_000, 0))
        assertNull(v.onStallDecision(948_000, 0))
    }

    @Test
    fun cursorPassingTheStuckHeight_isRecovered_once() {
        val v = FilterStallRestartVerifier()
        v.onRestarted(stuckAt = 948_000, nowMs = 1_000)
        assertNull("at the stuck height is not past it", v.onProgress(948_000, 2_000))
        val verdict = v.onProgress(948_001, 61_000)
        assertEquals(Verdict.Recovered(948_000, 948_001, 60_000), verdict)
        assertNull("settled once", v.onProgress(949_000, 70_000))
    }

    /**
     * A restarted engine resumes from its durable watermark, below the stuck
     * height: the cursor moving there is not recovery.
     */
    @Test
    fun cursorResumingBelowAndClimbingBack_isNotRecovery() {
        val v = FilterStallRestartVerifier()
        v.onRestarted(stuckAt = 948_000, nowMs = 0)
        assertNull(v.onProgress(4_999, 10_000))
        assertNull(v.onProgress(600_000, 20_000))
        assertNull(v.onProgress(948_000, 30_000))
    }

    /** The run 9 shape: two restarts, the cursor wedged at 948,000 both times. */
    @Test
    fun reWedgingAtTheSameHeight_isNotRecovered_andCounted() {
        val v = FilterStallRestartVerifier()
        v.onRestarted(stuckAt = 948_000, nowMs = 0)
        val first = v.onStallDecision(948_000, 600_000) as Verdict.NotRecovered
        assertTrue(first.sameHeight)
        assertEquals(1, first.consecutiveAtThisHeight)
        assertEquals(600_000, first.elapsedMs)

        v.onRestarted(stuckAt = 948_000, nowMs = 700_000)
        val second = v.onStallDecision(948_000, 1_900_000) as Verdict.NotRecovered
        assertEquals(2, second.consecutiveAtThisHeight)
        assertNull("settled", v.onStallDecision(948_000, 2_000_000))
    }

    @Test
    fun stallingShortOfTheStuckHeight_isNotRecovered_butNotTheSameHeight() {
        val v = FilterStallRestartVerifier()
        v.onRestarted(stuckAt = 948_000, nowMs = 0)
        val verdict = v.onStallDecision(700_000, 600_000) as Verdict.NotRecovered
        assertFalse(verdict.sameHeight)
    }

    @Test
    fun aRecoveryResetsTheSameHeightCount() {
        val v = FilterStallRestartVerifier()
        v.onRestarted(948_000, 0)
        v.onStallDecision(948_000, 1)
        v.onRestarted(948_000, 2)
        assertTrue(v.onProgress(948_500, 3) is Verdict.Recovered)
        v.onRestarted(948_000, 4)
        assertEquals(1, (v.onStallDecision(948_000, 5) as Verdict.NotRecovered).consecutiveAtThisHeight)
    }

    @Test
    fun aStallDecisionAfterTheCursorPassed_reportsRecovered() {
        val v = FilterStallRestartVerifier()
        v.onRestarted(948_000, 0)
        assertTrue(v.onStallDecision(1_200_000, 10) is Verdict.Recovered)
    }
}
