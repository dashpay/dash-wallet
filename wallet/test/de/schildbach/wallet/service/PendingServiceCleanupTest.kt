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
package de.schildbach.wallet.service

import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.CLEANUP_DEADLOCK_EXIT_MS
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.CleanupDeadlockAction
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.decideOnCleanupDeadlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingServiceCleanupTest {
    @Test
    fun scheduledCleanupIsTimedBeforeInitializationOrCleanupCoroutineRuns() {
        val cleanup = PendingServiceCleanup()
        cleanup.schedule(0L)

        assertTrue(cleanup.isPending())
        assertEquals(CLEANUP_DEADLOCK_EXIT_MS, cleanup.elapsedMs(CLEANUP_DEADLOCK_EXIT_MS))
        assertEquals(
            CleanupDeadlockAction.EXIT_PROCESS,
            decideOnCleanupDeadlock(cleanup.elapsedMs(CLEANUP_DEADLOCK_EXIT_MS), appVisible = false)
        )
    }

    @Test
    fun completedCleanupClearsTimerAndNextCleanupStartsFresh() {
        val cleanup = PendingServiceCleanup()
        assertFalse(cleanup.isPending())
        assertEquals(0L, cleanup.elapsedMs(1_000L))
        cleanup.schedule(1_000L)
        cleanup.finish()

        assertFalse(cleanup.isPending())
        assertEquals(0L, cleanup.elapsedMs(900_000L))
        cleanup.schedule(900_000L)
        assertEquals(10L, cleanup.elapsedMs(900_010L))
        assertEquals(
            CleanupDeadlockAction.STOP_SELF,
            decideOnCleanupDeadlock(cleanup.elapsedMs(900_010L), appVisible = false)
        )
    }

    @Test
    fun overlappingDestroyDoesNotRestartOrClearTheActiveTimer() {
        val cleanup = PendingServiceCleanup()
        cleanup.schedule(1_000L)
        cleanup.schedule(2_000L)
        assertEquals(2_000L, cleanup.elapsedMs(3_000L))

        // Either the duplicate or the owner may finish first; another destroy remains pending.
        cleanup.finish()
        assertTrue(cleanup.isPending())
        assertEquals(3_000L, cleanup.elapsedMs(4_000L))
        cleanup.finish()
        assertFalse(cleanup.isPending())
        assertEquals(0L, cleanup.elapsedMs(5_000L))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnbalancedCompletionWithoutMakingTheCountNegative() {
        PendingServiceCleanup().finish()
    }
}
