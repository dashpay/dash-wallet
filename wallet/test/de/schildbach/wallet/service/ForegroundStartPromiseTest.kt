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

import android.os.Bundle
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.START_AS_FOREGROUND_EXTRA
import de.schildbach.wallet.service.BlockchainServiceImpl.Companion.carriesForegroundStartPromise
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MO-995: `ForegroundServiceDidNotStartInTimeException` killed the process in
 * the field (2026-09-02 18:48:44, prod 12.0.0-sync).
 *
 * A start delivered by `startForegroundService()` arms a system promise —
 * `startForeground()` within a few seconds or the process dies. The call sat
 * inside `onStartCommand`'s coroutine behind `onCreateCompleted.await()`, so it
 * landed only after the service's whole async init, and not at all when the
 * service was concurrently torn down. An AlarmManager
 * `PendingIntent.getForegroundService` fired exactly as the idle detector was
 * stopping the service, and the two interleaved.
 *
 * The fix moved the call to run SYNCHRONOUSLY in `onStartCommand`. A missed
 * call is only testable if the DECISION to make it is separable from making it,
 * which is why [carriesForegroundStartPromise] is pure — the service lifecycle
 * itself is not reachable from a host-JVM test.
 */
class ForegroundStartPromiseTest {

    private fun extras(vararg keys: String): Bundle = mockk<Bundle> {
        keys.forEach { every { containsKey(it) } returns true }
        every { containsKey(not(match<String> { it in keys })) } returns false
    }

    @Test
    fun promise_isCarried_whenTheStartCommandIsMarkedAsForeground() {
        assertTrue(carriesForegroundStartPromise(extras(START_AS_FOREGROUND_EXTRA)))
    }

    /**
     * An ordinary `startService()` start carries NO promise, so promoting on it
     * would put the service in the foreground (and post a sync notification)
     * for starts that never asked. The condition must stay narrow — this is the
     * half that keeps the fix from over-promoting.
     */
    @Test
    fun promise_isNotCarried_byAnUnmarkedStartCommand() {
        assertFalse(carriesForegroundStartPromise(extras("some_other_extra")))
    }

    /**
     * Null extras: an intent with no extras at all. Must not promote, and must
     * not throw — this ran on every plain start before the fix existed.
     */
    @Test
    fun promise_isNotCarried_whenThereAreNoExtras() {
        assertFalse(carriesForegroundStartPromise(null))
    }

    /**
     * A NULL intent is the system re-delivering a start after the process was
     * killed. No fresh `startForegroundService()` happened, so there is no
     * promise to satisfy and promoting would be wrong.
     */
    @Test
    fun promise_isNotCarried_byASystemRedeliveryWithNoIntent() {
        // shouldPromoteToForeground(null) reduces to this — pinned so the null
        // path cannot regress into a promotion.
        assertFalse(carriesForegroundStartPromise((null as Bundle?)))
    }
}
