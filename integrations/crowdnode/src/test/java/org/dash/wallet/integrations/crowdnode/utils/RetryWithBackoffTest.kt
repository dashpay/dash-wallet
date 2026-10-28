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

package org.dash.wallet.integrations.crowdnode.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryWithBackoffTest {
    @Test
    fun succeedsAfterFailures_waitsWithGrowingDelay() = runTest {
        val attemptTimes = mutableListOf<Long>()

        val succeeded = retryWithBackoff(5, 1_000, 30_000) {
            attemptTimes.add(currentTime)
            if (attemptTimes.size < 3) throw IOException("transient")
        }

        assertTrue(succeeded)
        assertEquals(listOf(0L, 1_000L, 3_000L), attemptTimes)
    }

    @Test
    fun keepsFailing_givesUpAfterMaxAttemptsWithCappedDelay() = runTest {
        val failedAttempts = mutableListOf<Int>()

        val succeeded = retryWithBackoff(
            4,
            1_000,
            2_000,
            onFailure = { attempt, _ -> failedAttempts.add(attempt) }
        ) {
            throw IOException("persistent")
        }

        assertFalse(succeeded)
        assertEquals(listOf(1, 2, 3, 4), failedAttempts)
        // 1s + 2s + 2s (capped), and no delay after the last attempt
        assertEquals(5_000L, currentTime)
    }

    @Test(expected = CancellationException::class)
    fun cancellation_propagatesWithoutRetrying() = runTest {
        var attempts = 0

        try {
            retryWithBackoff(5, 1_000, 30_000) {
                attempts++
                throw CancellationException("cancelled")
            }
        } finally {
            assertEquals(1, attempts)
        }
    }
}
