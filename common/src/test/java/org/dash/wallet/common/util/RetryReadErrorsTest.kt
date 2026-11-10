package org.dash.wallet.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryReadErrorsTest {

    private class TestError : Error("fatal")

    @Test
    fun exception_emitsFallbackThenRecovers() = runTest {
        var reads = 0
        val source = flow {
            if (reads++ == 0) throw IOException("unreadable")
            emit(7)
        }

        val values = source.retryReadErrors(fallback = -1, retryDelayMs = { 0L }).take(2).toList()

        assertEquals(listOf(-1, 7), values)
        assertEquals(2, reads)
    }

    @Test
    fun repeatedExceptions_countAttemptsAndUseTheDelayFunction() = runTest {
        var reads = 0
        val source = flow {
            if (reads++ < 3) throw IOException("unreadable $reads")
            emit(1)
        }
        val errorAttempts = mutableListOf<Long>()
        val delayAttempts = mutableListOf<Long>()
        val causes = mutableListOf<Exception>()

        val values = source
            .retryReadErrors(
                fallback = 0,
                retryDelayMs = { attempt ->
                    delayAttempts += attempt
                    (attempt + 1) * 1_000L
                }
            ) { cause, attempt ->
                causes += cause
                errorAttempts += attempt
            }
            .take(4)
            .toList()

        assertEquals(listOf(0, 0, 0, 1), values)
        assertEquals(listOf(0L, 1L, 2L), errorAttempts)
        assertEquals(listOf(0L, 1L, 2L), delayAttempts)
        assertEquals(listOf("unreadable 1", "unreadable 2", "unreadable 3"), causes.map { it.message })
        // 1 s + 2 s + 3 s of virtual back-off
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun error_propagatesWithoutFallback() = runTest {
        val error = TestError()
        val emitted = mutableListOf<Int>()
        var onErrorCalls = 0

        try {
            flow<Int> { throw error }
                .retryReadErrors(fallback = -1, retryDelayMs = { 0L }) { _, _ -> onErrorCalls++ }
                .collect { emitted += it }
            fail("expected the Error to propagate")
        } catch (e: TestError) {
            assertSame(error, e)
        }

        assertEquals(emptyList<Int>(), emitted)
        assertEquals(0, onErrorCalls)
    }

    @Test
    fun cancellationException_propagatesWithoutFallback() = runTest {
        val cancellation = CancellationException("stop")
        val emitted = mutableListOf<Int>()
        var reads = 0

        try {
            flow<Int> {
                reads++
                throw cancellation
            }
                .retryReadErrors(fallback = -1, retryDelayMs = { 0L })
                .collect { emitted += it }
            fail("expected the CancellationException to propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        assertEquals(emptyList<Int>(), emitted)
        assertEquals(1, reads)
    }

    @Test
    fun defaultDelay_isReadRetryDelayMs() = runTest {
        var reads = 0
        val source = flow {
            if (reads++ == 0) throw IOException("unreadable")
            emit(true)
        }

        val values = source.retryReadErrors(false).take(2).toList()

        assertEquals(listOf(false, true), values)
        assertEquals(readRetryDelayMs(0), currentTime)
    }
}
