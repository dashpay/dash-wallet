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

package de.schildbach.wallet.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackActionTest {
    @Test
    fun lockScreenShowing_isLeftToTheLockScreen() {
        for (count in listOf(0, 1, 3)) {
            for (navCanPop in listOf(true, false)) {
                for (root in listOf(true, false)) {
                    assertEquals(HomeBackAction.IGNORE, homeBackAction(true, count, navCanPop, root))
                }
            }
        }
    }

    @Test
    fun fragmentOnTheBackStack_isPopped() {
        assertEquals(HomeBackAction.POP_FRAGMENT, homeBackAction(false, 1, navCanPop = true, isTaskRoot = true))
        assertEquals(HomeBackAction.POP_FRAGMENT, homeBackAction(false, 2, navCanPop = false, isTaskRoot = false))
    }

    @Test
    fun tabOrSubScreen_popsTheNavStack_backToHome() {
        // e.g. More on top of Home: back returns to Home, never leaves the app
        assertEquals(HomeBackAction.POP_NAVIGATION, homeBackAction(false, 0, navCanPop = true, isTaskRoot = true))
        assertEquals(HomeBackAction.POP_NAVIGATION, homeBackAction(false, 0, navCanPop = true, isTaskRoot = false))
    }

    @Test
    fun home_asTaskRoot_isConsumed() {
        assertEquals(HomeBackAction.CONSUME, homeBackAction(false, 0, navCanPop = false, isTaskRoot = true))
    }

    @Test
    fun home_insideAnotherAppsTask_finishes() {
        assertEquals(HomeBackAction.FINISH, homeBackAction(false, 0, navCanPop = false, isTaskRoot = false))
    }
}
