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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WalletSecretWriteFenceTest {

    @Test
    fun `a fresh fence is open and permits writes`() {
        val fence = WalletSecretWriteFence()
        assertFalse(fence.isRaised)
        assertEquals(7, fence.withSecretWritePermit("write") { 7 })
        assertTrue(fence.tryAcquire("write"))
        fence.release()
    }

    @Test
    fun `a raised fence refuses permits until lowered`() {
        val fence = WalletSecretWriteFence()
        fence.raise()

        assertFalse(fence.tryAcquire("write"))
        try {
            fence.withSecretWritePermit("write") { fail("must not run under a raised fence") }
            fail("expected WalletWipeInProgressException")
        } catch (expected: WalletWipeInProgressException) {
            // refused
        }
        assertEquals("refused", fence.ifOpen("write", refused = { "refused" }) { "ran" })

        fence.lower()
        assertEquals("ran", fence.ifOpen("write", refused = { "refused" }) { "ran" })
    }

    @Test
    fun `halting SDK starts raises the fence and lowering clears both`() {
        val fence = WalletSecretWriteFence()
        assertFalse(fence.isSdkRuntimeHalted)

        fence.haltSdkRuntime()

        assertTrue(fence.isSdkRuntimeHalted)
        assertTrue(fence.isRaised)

        fence.lower()

        assertFalse(fence.isSdkRuntimeHalted)
        assertFalse(fence.isRaised)
    }

    @Test
    fun `a producer tracked under a raised fence never runs`() = runBlocking {
        val fence = WalletSecretWriteFence()
        fence.raise()
        var ran = false
        val job = launch(start = CoroutineStart.LAZY) { ran = true }

        fence.track(job)
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(ran)
    }

    @Test
    fun `quiesce cancels and joins tracked producers`() = runBlocking {
        val fence = WalletSecretWriteFence()
        val job = launch(Dispatchers.Default) { awaitCancellation() }
        fence.track(job)

        assertTrue(fence.raiseAndQuiesce(5_000L))
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `quiesce times out on a permit that is never released`() = runBlocking {
        val fence = WalletSecretWriteFence()
        assertTrue(fence.tryAcquire("stuck native write"))

        assertFalse(fence.raiseAndQuiesce(100L))

        fence.release()
        assertTrue("drains once released", fence.raiseAndQuiesce(1_000L))
    }

    @Test
    fun `a completed producer is forgotten`() = runBlocking {
        val fence = WalletSecretWriteFence()
        val job = launch { }
        fence.track(job)
        job.join()

        assertTrue(fence.raiseAndQuiesce(1_000L))
    }
}
