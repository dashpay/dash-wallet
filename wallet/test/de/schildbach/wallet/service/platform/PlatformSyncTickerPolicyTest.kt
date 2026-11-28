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

package de.schildbach.wallet.service.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background-aware cadence of the 15 s platform ticker (§10.4, finding 11). */
class PlatformSyncTickerPolicyTest {

    @Test
    fun foreground_runsEveryTick() {
        for (ticks in 0..30) assertTrue(contactTickDue(inBackground = false, ticksSinceLastRun = ticks))
    }

    @Test
    fun background_runsEveryFiveMinutes() {
        for (ticks in 1 until BACKGROUND_CONTACT_TICKS) {
            assertFalse("tick $ticks is inside the background hold", contactTickDue(inBackground = true, ticksSinceLastRun = ticks))
        }
        assertTrue(contactTickDue(inBackground = true, ticksSinceLastRun = BACKGROUND_CONTACT_TICKS))
        assertTrue(contactTickDue(inBackground = true, ticksSinceLastRun = BACKGROUND_CONTACT_TICKS + 5))
    }

    @Test
    fun firstTickAfterStart_runsEvenInTheBackground() {
        // The counter is seeded at the threshold so a cold start still does one pass.
        assertTrue(contactTickDue(inBackground = true, ticksSinceLastRun = BACKGROUND_CONTACT_TICKS))
    }
}
