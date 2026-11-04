package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResponseConfirmationTest {
    @Test fun `delayed exact response confirms on third read without another publication`() = runTest {
        val times = mutableListOf<Long>()
        val outcomes = mutableListOf<String>()
        awaitResponseConfirmation(read = {
            times.add(currentTime)
            if (times.size == 3) listOf("exact") else emptyList()
        }, exact = { it == "exact" }, report = { _, outcome -> outcomes.add(outcome) })
        assertEquals(listOf(0L, 2_000L, 6_000L), times)
        assertEquals(listOf("response absent", "response absent", "exact response confirmed"), outcomes)
    }

    @Test fun `existing exact response confirms immediately`() = runTest {
        var reads = 0
        awaitResponseConfirmation({ reads++; listOf("exact") }, { it == "exact" })
        assertEquals(1, reads)
        assertEquals(0L, currentTime)
    }

    @Test fun `missing response exhausts three reads with recoverable error`() = runTest {
        var reads = 0
        try {
            awaitResponseConfirmation<String>({ reads++; emptyList() }, { true })
            fail("Missing response must not confirm")
        } catch (ex: IllegalStateException) {
            assertEquals("Response not confirmed. Retry to recover.", ex.message)
        }
        assertEquals(3, reads)
        assertEquals(6_000L, currentTime)
    }

    @Test fun `conflict is rejected even alongside an exact response`() = runTest {
        var reads = 0
        try {
            awaitResponseConfirmation({ reads++; listOf("exact", "conflict") }, { it == "exact" })
            fail("Conflicting response must fail")
        } catch (ex: IllegalStateException) {
            assertEquals("Conflicting response exists for this QR", ex.message)
        }
        assertEquals(1, reads)
    }

    @Test fun `query errors propagate without retry or sensitive diagnostic data`() = runTest {
        val failure = IllegalStateException("proof rejected")
        val outcomes = mutableListOf<String>()
        var reads = 0
        try {
            awaitResponseConfirmation<String>({ reads++; throw failure }, { true }, { _, outcome -> outcomes.add(outcome) })
            fail("Query failure must propagate")
        } catch (ex: IllegalStateException) {
            assertSame(failure, ex)
        }
        assertEquals(1, reads)
        assertEquals(listOf("query failed"), outcomes)
    }

    @Test fun `cancellation prevents subsequent confirmation reads`() = runTest {
        var reads = 0
        val job = async { awaitResponseConfirmation<String>({ reads++; emptyList() }, { true }) }
        runCurrent()
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
