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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DpnsRegistrationLockTest {

    @Test
    fun `a second caller for the same identity waits and is told it waited`() = runTest {
        val lock = DpnsRegistrationLock()
        val gate = CompletableDeferred<Unit>()
        val first = async { lock.withIdentityLock("id") { waited -> gate.await(); waited } }
        val second = async { lock.withIdentityLock("id") { waited -> waited } }
        advanceUntilIdle()
        assertFalse(second.isCompleted)

        gate.complete(Unit)

        assertFalse(first.await())
        assertTrue(second.await())
    }

    @Test
    fun `different identities do not wait for each other`() = runTest {
        val lock = DpnsRegistrationLock()
        val gate = CompletableDeferred<Unit>()
        val first = async { lock.withIdentityLock("a") { gate.await() } }
        val other = async { lock.withIdentityLock("b") { waited -> waited } }
        advanceUntilIdle()

        assertFalse(other.await())
        gate.complete(Unit)
        first.await()
    }

    @Test
    fun `a cancelled waiter and a failing holder both leave the lock free`() = runTest {
        val lock = DpnsRegistrationLock()
        val gate = CompletableDeferred<Unit>()
        val holder = async {
            runCatching { lock.withIdentityLock("id") { gate.await(); error("boom") } }
        }
        val waiter = launch { lock.withIdentityLock("id") { } }
        advanceUntilIdle()
        waiter.cancel()
        advanceUntilIdle()

        gate.complete(Unit)
        assertTrue(holder.await().isFailure)

        assertEquals(false, lock.withIdentityLock("id") { waited -> waited })
    }
}
