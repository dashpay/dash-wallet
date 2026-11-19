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
import org.junit.Test

/**
 * The engine reports its scan height only when it goes up, so a rewind the app arms is
 * invisible in those reports until the rescan commits a batch. The copy is lowered at the arm.
 */
class CommittedScanCursorTest {
    private val cursor = CommittedScanCursor()

    @Test
    fun anArm_lowersTheCopyAtOnce_andTheRescanClimbsFromThere() {
        cursor.seed(1_500_000L)

        cursor.rescanArmed(900_000L)
        assertEquals(900_000L, cursor.value)

        cursor.engineAdvanced(905_000L)
        assertEquals(905_000L, cursor.value)
        cursor.engineAdvanced(1_500_010L)
        assertEquals(1_500_010L, cursor.value)
    }

    @Test
    fun anArmAtOrAboveTheCopy_changesNothing() {
        cursor.seed(900_000L)

        cursor.rescanArmed(900_000L)
        assertEquals(900_000L, cursor.value)

        // No floor is kept: a later session seeds as usual.
        cursor.sessionStopped()
        cursor.seed(950_000L)
        assertEquals(950_000L, cursor.value)
    }

    /** The saved height is not lowered by the arm; a restart of the scanner must not bring it back. */
    @Test
    fun aStopAndStartBeforeTheEngineReports_keepsTheArmedFloor() {
        cursor.seed(1_500_000L)
        cursor.rescanArmed(900_000L)

        cursor.sessionStopped()
        assertEquals("unknown while stopped", 0L, cursor.value)
        cursor.seed(1_500_000L)
        assertEquals(900_000L, cursor.value)

        // The engine's own report ends the floor.
        cursor.engineAdvanced(910_000L)
        cursor.sessionStopped()
        cursor.seed(910_000L)
        assertEquals(910_000L, cursor.value)
    }

    /** The usual case: the rescan is armed with the scanner stopped, before it starts. */
    @Test
    fun anArmWhileTheScannerIsStopped_isShownByTheSeedOfItsStart() {
        cursor.rescanArmed(900_000L)
        assertEquals("unknown while stopped", 0L, cursor.value)

        cursor.seed(1_500_000L)
        assertEquals(900_000L, cursor.value)
    }

    @Test
    fun aFailedSeedAfterAnArm_readsTheFloor_notUnknown() {
        cursor.seed(1_500_000L)
        cursor.rescanArmed(900_000L)
        cursor.sessionStopped()

        cursor.seed(0L)
        assertEquals(900_000L, cursor.value)
    }

    @Test
    fun aRewindToHeightZero_isStillVisible() {
        cursor.seed(1_500_000L)

        cursor.rescanArmed(0L)
        assertEquals("0 would read as unknown", 1L, cursor.value)
    }
}
