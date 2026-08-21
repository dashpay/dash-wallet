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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Behavior of [boundedSdkPlatformQuery] — the cert-resilience bound.
 *
 * The failure it exists for: mainnet DAPI nodes serving EXPIRED TLS CERTS make
 * the Kotlin-SDK Platform query cost ~1.8 min before the wrapper's existing
 * legacy-Platform fallback answers in ~0.5 s. Crucially the SDK query bottoms
 * out in a BLOCKING JNI call, so it cannot observe cancellation — the test at
 * [a thread-blocking query is abandoned at the budget] blocks a thread with no
 * suspension point on purpose, which is exactly why a plain
 * `withTimeoutOrNull { source.query() }` would not have worked.
 */
class SdkPlatformQueryBudgetTest {

    /** A completed query returns its value boxed — never confused with a timeout. */
    @Test
    fun `a completed query returns its value`() = runBlocking {
        val result = boundedSdkPlatformQuery("fast", timeoutMs = 5_000) { "payload" }
        assertEquals("payload", result?.orElse(null))
    }

    /**
     * The SDK's OWN null ("definitively nothing on Platform") must stay
     * distinguishable from the budget expiring, or callers that map null to
     * "no such name / no profile" would start falling back instead.
     */
    @Test
    fun `a null result is boxed, not reported as a timeout`() = runBlocking {
        val result = boundedSdkPlatformQuery("empty", timeoutMs = 5_000) { null as String? }
        assertTrue("expected a present Optional box", result != null)
        assertFalse("expected an EMPTY box, not a timeout", result!!.isPresent)
    }

    /**
     * The core contract: a query that blocks its thread with NO suspension point
     * — the shape of the SDK's blocking JNI call — still returns control to the
     * caller at the budget, as null (the callers' existing fall-back signal).
     */
    @Test
    fun `a thread-blocking query is abandoned at the budget`() = runBlocking {
        val release = CountDownLatch(1)
        val startedAt = System.currentTimeMillis()
        val result = boundedSdkPlatformQuery("stuck", timeoutMs = 150) {
            // Uncancellable on purpose: Thread.sleep/await inside a coroutine is
            // exactly the non-suspending native frame this bound has to survive.
            release.await(30, TimeUnit.SECONDS)
            "never used"
        }
        val elapsed = System.currentTimeMillis() - startedAt
        release.countDown()

        assertNull("expected the timeout's fall-back signal", result)
        assertTrue("returned only after ${elapsed}ms; the budget was 150ms", elapsed < 5_000)
    }

    /** Failures still reach the caller's existing catch unchanged. */
    @Test
    fun `an exception from the query propagates`() = runBlocking {
        val thrown = try {
            boundedSdkPlatformQuery("boom", timeoutMs = 5_000) { error("native failure") }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertEquals("native failure", thrown?.message)
    }

    /**
     * Cancellation of the CALLER must not be swallowed — every wrapper's
     * `if (t is CancellationException) throw t` depends on it.
     */
    @Test
    fun `caller cancellation is not swallowed`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()

        val caller = async {
            try {
                boundedSdkPlatformQuery("cancelled", timeoutMs = 30_000) {
                    entered.complete(Unit)
                    CompletableDeferred<String>().await() // never completes
                }
            } catch (t: Throwable) {
                observed.complete(t)
                throw t
            }
        }

        entered.await()
        caller.cancel()

        assertTrue(
            "expected a CancellationException, got ${observed.await()}",
            observed.await() is CancellationException
        )
    }
}
