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

import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.CLEANUP_DEADLOCK_EXIT_MS
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.CleanupDeadlockAction
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.decideOnCleanupDeadlock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan §37 (Andrei, 2026-09-22, SM-A536B, `12000017`): the blockchain service's
 * onDestroy cleanup parked behind a native SDK call and never finished. From
 * 06:31 to 10:00 UTC every alarm-driven start logged "deadlock in onDestroy"
 * and stopped itself — twelve times — while the process lived on with the
 * engine off. Nothing inside that process could recover it.
 *
 * [decideOnCleanupDeadlock] is what a refused start does next. These pin both
 * axes: the bound (a cleanup stuck past it will not finish) and visibility (never
 * close the app in the user's face).
 */
class CleanupDeadlockPolicyTest {

    /** THE FIELD CASE: 19 minutes stuck, app in the background — end the process. */
    @Test
    fun exits_whenStuckPastTheBound_andTheAppIsInTheBackground() {
        assertEquals(
            CleanupDeadlockAction.EXIT_PROCESS,
            decideOnCleanupDeadlock(stuckForMs = 19 * 60_000L, appVisible = false)
        )
        assertEquals(
            "the bound itself qualifies",
            CleanupDeadlockAction.EXIT_PROCESS,
            decideOnCleanupDeadlock(stuckForMs = CLEANUP_DEADLOCK_EXIT_MS, appVisible = false)
        )
    }

    /** Andrei at 09:53: the start his own foreground triggered is refused, but the app stays up. */
    @Test
    fun stopsSelfOnly_whenTheAppIsVisible_howeverLongTheCleanupHasBeenStuck() {
        assertEquals(
            CleanupDeadlockAction.STOP_SELF,
            decideOnCleanupDeadlock(stuckForMs = 4 * 60 * 60_000L, appVisible = true)
        )
    }

    /** A cleanup that is merely slow (the reset-path cleanup took 46 s on 2026-09-22) is not a deadlock. */
    @Test
    fun stopsSelfOnly_whenTheCleanupHasNotBeenStuckLong() {
        assertEquals(
            CleanupDeadlockAction.STOP_SELF,
            decideOnCleanupDeadlock(stuckForMs = 46_000L, appVisible = false)
        )
        assertEquals(
            CleanupDeadlockAction.STOP_SELF,
            decideOnCleanupDeadlock(stuckForMs = CLEANUP_DEADLOCK_EXIT_MS - 1, appVisible = false)
        )
        assertEquals(
            "no cleanup ever started in this process reads as 0 ms stuck",
            CleanupDeadlockAction.STOP_SELF,
            decideOnCleanupDeadlock(stuckForMs = 0L, appVisible = false)
        )
    }
}
