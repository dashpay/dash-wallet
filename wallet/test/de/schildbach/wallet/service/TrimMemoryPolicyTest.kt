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

import android.content.ComponentCallbacks2
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.shouldStopForMemoryPressure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MO-995: `onTrimMemory` used `level >= TRIM_MEMORY_BACKGROUND`, which is wrong
 * in both directions — 40 means "the user left the app", while the real
 * running-low signals (10, 15) are numerically below it and were ignored.
 *
 * Field log (2026-09-06, testnet 12000004, HONOR PTP-N49): the engine was torn
 * down on every backgrounding, so a from-genesis scan got three restarts in
 * five minutes, never caught up, and the cutover never committed — Buy Credits
 * failed as a result.
 *
 * These pin EVERY documented level, because the bug was an off-by-threshold on
 * a scale where the numbers are not ordered by severity.
 */
class TrimMemoryPolicyTest {

    /** The levels that mean "you are about to be killed" — tear down. */
    @Test
    fun stops_onlyOnImminentDeath() {
        assertTrue(
            "COMPLETE (80) is the top of the LRU kill list",
            shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        )
        assertTrue(
            "RUNNING_CRITICAL (15) means the system is already killing background processes",
            shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        )
    }

    /**
     * THE REGRESSION. 40 is "your process went onto the LRU background list" —
     * ordinary backgrounding, not memory pressure. A long L1 scan must survive
     * the user leaving the app.
     */
    @Test
    fun doesNotStop_whenTheUserSimplyBackgroundsTheApp() {
        assertFalse(
            "BACKGROUND (40) is backgrounding, not low memory",
            shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        )
        assertFalse(
            "UI_HIDDEN (20) is the UI going away",
            shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        )
    }

    /**
     * MODERATE (60) is mid-LRU — not imminent danger, and deliberately excluded:
     * an hours-long from-genesis scan is exactly the workload that has to ride
     * it out. Pinned so the choice is explicit rather than incidental.
     */
    @Test
    fun doesNotStop_atMidLruModerate() {
        assertFalse(shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
    }

    /** The milder running-* levels are advisory; trimming caches, not dying. */
    @Test
    fun doesNotStop_onTheAdvisoryRunningLevels() {
        assertFalse(shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(shouldStopForMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }

    /**
     * The scale is not ordered by severity (15 is graver than 40), so a plain
     * `>=` cannot express the policy. Pin the whole documented set at once so a
     * future `>=` rewrite fails loudly.
     */
    @Test
    fun theFullDocumentedScale_behavesAsSpecified() {
        val expected = mapOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE to false, // 5
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to false, // 10
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL to true, // 15
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN to false, // 20
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND to false, // 40
            ComponentCallbacks2.TRIM_MEMORY_MODERATE to false, // 60
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE to true // 80
        )
        expected.forEach { (level, shouldStop) ->
            org.junit.Assert.assertEquals(
                "level $level",
                shouldStop,
                shouldStopForMemoryPressure(level)
            )
        }
    }
}
