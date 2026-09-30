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

import de.schildbach.wallet.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicAlarmTierTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun intervals_followLastUse() {
        assertEquals(15 * minute, PeriodicAlarmTier.intervalMs(0))
        assertEquals(15 * minute, PeriodicAlarmTier.intervalMs(Constants.LAST_USAGE_THRESHOLD_JUST_MS - 1))
        assertEquals(12 * hour, PeriodicAlarmTier.intervalMs(Constants.LAST_USAGE_THRESHOLD_JUST_MS))
        assertEquals(12 * hour, PeriodicAlarmTier.intervalMs(Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS - 1))
        assertEquals(day, PeriodicAlarmTier.intervalMs(Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS))
    }

    @Test
    fun startReason_isTheLabelTheSchedulerUsed() {
        assertEquals("periodic-15min", PeriodicAlarmTier.startReason(15 * minute))
        assertEquals("periodic-720min", PeriodicAlarmTier.startReason(12 * hour))
        assertEquals("periodic-1440min", PeriodicAlarmTier.startReason(day))
    }

    /** The field case: a 15-minute arm still firing hours after the last use. */
    @Test
    fun aFifteenMinuteFire_afterAnHourWithoutUse_needsARearm() {
        assertTrue(PeriodicAlarmTier.needsRearm("periodic-15min", 74 * minute))
        assertTrue(PeriodicAlarmTier.needsRearm("periodic-720min", 3 * day))
    }

    @Test
    fun aFireInTheCurrentTier_needsNoRearm() {
        assertFalse(PeriodicAlarmTier.needsRearm("periodic-15min", 5 * minute))
        assertFalse(PeriodicAlarmTier.needsRearm("periodic-720min", 5 * hour))
        assertFalse(PeriodicAlarmTier.needsRearm("periodic-1440min", 5 * day))
    }

    @Test
    fun otherStartReasons_areLeftAlone() {
        assertFalse(PeriodicAlarmTier.needsRearm(null, 5 * day))
        assertFalse(PeriodicAlarmTier.needsRearm("restart-15min", 5 * day))
        assertFalse(PeriodicAlarmTier.needsRearm("periodic-xmin", 5 * day))
    }
}
