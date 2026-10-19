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

package org.dash.wallet.common.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard every receive-address failure handler goes through.
 *
 * The hazard it exists for is specific. A live receive-address read parks on an
 * uncancellable `Thread.sleep` (up to five seconds waiting for the SDK engine to
 * bind) inside `withContext(Dispatchers.IO)`, so the screen that started it can
 * be long gone by the time the read unwinds. Cancelling a `withContext` discards
 * a SUCCESSFUL result and resumes the caller with `CancellationException` — but
 * an exception thrown by its BODY is delivered as itself. The failure handler
 * therefore runs on a dead screen, and `requireContext()` there throws
 * `IllegalStateException` instead of offering the retry.
 *
 * [aCancelledWithContextStillDeliversItsBodysException] pins that library
 * behaviour, because everything else here is only worth doing if it holds.
 */
class AttachedUiTest {

    /** A stand-in for `Fragment.getContext()`: present while attached. */
    private class Host

    /**
     * Run [handle] as the failure handler of a read that is PARKED when its
     * coroutine is cancelled and only then fails — the exact ordering the real
     * engine read produces, and the one that makes the handler run on a screen
     * that is already gone.
     *
     * Returns once the coroutine has fully unwound, so nothing a caller asserts
     * afterwards can race it.
     */
    private fun runFailureAfterCancellation(handle: suspend () -> Unit): Boolean {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handlerRan = AtomicBoolean(false)
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    parked.countDown()
                    check(release.await(AWAIT_SECONDS, TimeUnit.SECONDS)) { "the read was never released" }
                    throw ReceiveAddressUnavailableException()
                }
            } catch (ex: ReceiveAddressUnavailableException) {
                handlerRan.set(true)
                handle()
            }
        }

        assertTrue("the read must have parked", parked.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        // Rotation, a back press, the page leaving the pager: the scope is
        // cancelled while the read cannot answer cancellation.
        job.cancel()
        release.countDown()
        runBlocking { job.join() }

        return handlerRan.get()
    }

    @Test
    fun aCancelledWithContextStillDeliversItsBodysException() {
        // The premise. If this ever stops holding, the guard below is dead code
        // rather than wrong — but so is the reasoning in every handler that uses
        // it, which is why it is asserted rather than assumed.
        assertTrue(
            "a cancelled withContext whose body throws must deliver that throw to the catch",
            runFailureAfterCancellation { }
        )
    }

    @Test
    fun aFailureArrivingAfterCancellationSurfacesNothing() {
        val surfaced = AtomicInteger(0)
        // Deliberately an ATTACHED host. This models the window the real
        // fragments sit in: `onDestroy` has cancelled the scope but `onDetach`
        // has not yet nulled the context, so the host check cannot help and only
        // the cancellation check can.
        val host = Host()

        val handlerRan = runFailureAfterCancellation {
            runIfStillAttached(host) { surfaced.incrementAndGet() }
        }

        // Both halves matter. If the handler did NOT run, the assertion below
        // would pass for the wrong reason and cover nothing.
        assertTrue("the failure handler must still run — that is the hazard", handlerRan)
        assertEquals("but it must surface nothing on a cancelled coroutine", 0, surfaced.get())
    }

    @Test
    fun aDetachedHostSurfacesNothing() = runBlocking {
        val surfaced = AtomicInteger(0)

        // What `Fragment.getContext()` returns once the fragment has detached,
        // and where `requireContext()` throws instead.
        assertFalse(runIfStillAttached(null) { surfaced.incrementAndGet() })
        assertEquals(0, surfaced.get())
    }

    @Test
    fun anAttachedHostOnALiveCoroutineSurfacesTheFailure() = runBlocking {
        // The guard must not be a blanket "never show anything": the retry
        // prompt is the whole point of failing closed.
        val surfacedOn = AtomicReference<Host?>(null)
        val host = Host()

        assertTrue(runIfStillAttached(host) { surfacedOn.set(it) })
        assertEquals(host, surfacedOn.get())
    }

    companion object {
        private const val AWAIT_SECONDS = 10L
    }
}
