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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedScreenActionsTest {
    @Test
    fun `wipe recovery leaves only report and close even when seed recovery is also needed`() {
        for (safeMode in listOf(false, true)) {
            for (seed in listOf(false, true)) {
                for (firstShow in listOf(false, true)) {
                    val actions = degradedScreenActions(
                        wipeRecoveryRequired = true,
                        safeMode = safeMode,
                        recoveryFromSeedNeeded = seed,
                        firstShow = firstShow
                    )
                    assertTrue(actions.showWipeRecoveryMessage)
                    assertFalse("no restore from seed under a wipe marker", actions.offerSeedRecovery)
                    assertFalse("no load retry under a wipe marker", actions.offerSafeModeRetry)
                    assertTrue(actions.reportIsPrimary)
                    assertFalse(actions.autoShowReport)
                }
            }
        }
    }

    @Test
    fun `without a wipe marker the screen keeps its existing actions`() {
        assertEquals(
            DegradedScreenActions(false, false, true, false, true),
            degradedScreenActions(false, safeMode = false, recoveryFromSeedNeeded = true, firstShow = true)
        )
        assertEquals(
            DegradedScreenActions(false, true, false, false, false),
            degradedScreenActions(false, safeMode = true, recoveryFromSeedNeeded = false, firstShow = false)
        )
        // A seed-recovery verdict outranks the safe-mode retry.
        assertEquals(
            DegradedScreenActions(false, false, true, false, true),
            degradedScreenActions(false, safeMode = true, recoveryFromSeedNeeded = true, firstShow = true)
        )
        assertEquals(
            DegradedScreenActions(false, false, false, true, true),
            degradedScreenActions(false, safeMode = false, recoveryFromSeedNeeded = false, firstShow = true)
        )
    }
}
