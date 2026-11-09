package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrantConfirmationTest {
    @Test fun `confirmed state returns immediately`() = runTest {
        var reads = 0
        assertEquals(1, awaitGrantConfirmation({ ++reads }, { true }))
        assertEquals(1, reads)
        assertEquals(0L, currentTime)
    }

    @Test fun `stale state is polled three times with backoff`() = runTest {
        val times = mutableListOf<Long>()
        val result = awaitGrantConfirmation({ times.add(currentTime); times.size }, { it == 3 })
        assertEquals(3, result)
        assertEquals(listOf(0L, 2_000L, 6_000L), times)
    }

    @Test fun `second read success skips final retry`() = runTest {
        var reads = 0
        assertEquals(2, awaitGrantConfirmation({ ++reads }, { it == 2 }))
        assertEquals(2_000L, currentTime)
        assertEquals(2, reads)
    }

    @Test fun `exhaustion never reaches publication`() = runTest {
        var reads = 0
        var published = false
        try {
            awaitGrantConfirmation({ ++reads }, { false })
            published = true
            fail("Unconfirmed grant must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Response was not published"))
        }
        assertFalse(published)
        assertEquals(3, reads)
        assertEquals(6_000L, currentTime)
    }

    @Test fun `read errors propagate without retry`() = runTest {
        val failure = IllegalStateException("Proof verification failed")
        var reads = 0
        try {
            awaitGrantConfirmation<Int>({ reads++; throw failure }, { true })
            fail("Read error must propagate")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertEquals(1, reads)
        assertEquals(0L, currentTime)
    }

    @Test fun `cancellation during backoff prevents more reads`() = runTest {
        var reads = 0
        val job = async { awaitGrantConfirmation({ ++reads }, { false }) }
        runCurrent()
        assertEquals(1, reads)
        job.cancel()
        advanceUntilIdle()
        try {
            job.await()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, reads)
        }
    }
}
