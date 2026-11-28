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

package de.schildbach.wallet.service.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reset Wallet, or cancelling the publish worker, must stop a metadata publish
 * that is already under way before its next document is submitted.
 */
class TxMetadataDocumentBoundaryTest {

    private val documents = listOf("doc 1", "doc 2", "doc 3")
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val firstDocumentSubmitting = CountDownLatch(1)
    private val releaseFirstDocument = CountDownLatch(1)

    /**
     * The shape of `BlockchainIdentity.publishTxMetaData` (dash-sdk-kotlin
     * 4.0.1): a blocking loop that submits each document on the calling thread
     * and reports progress before the first and after each one. The first
     * submission blocks until the test releases it.
     */
    private fun sdkPublish(progressListener: (Int) -> Unit) {
        progressListener(0)
        progressListener(10)
        documents.forEachIndexed { i, document ->
            submitted += document
            if (i == 0) {
                firstDocumentSubmitting.countDown()
                check(releaseFirstDocument.await(5, TimeUnit.SECONDS)) { "first document never released" }
            }
            progressListener(10 + i * 100 / documents.size)
        }
        progressListener(100)
    }

    private fun CoroutineScope.startPublish(shouldStop: () -> Boolean, progress: MutableList<Int> = mutableListOf()) =
        async(Dispatchers.IO) {
            publishStoppingBetweenDocuments(shouldStop, onProgress = { progress += it }) { listener ->
                sdkPublish(listener)
            }
        }

    @Test
    fun aResetWhileTheFirstDocumentIsBlockedSubmitsNoMore() = runBlocking {
        val resetInProgress = AtomicBoolean(false)
        val publish = startPublish(shouldStop = resetInProgress::get)
        assertTrue(firstDocumentSubmitting.await(5, TimeUnit.SECONDS))

        resetInProgress.set(true)
        releaseFirstDocument.countDown()

        try {
            publish.await()
            fail("the publish ran to completion through a wallet reset")
        } catch (_: TxMetadataPublishStoppedException) {
        }
        assertEquals(listOf("doc 1"), submitted)
    }

    @Test
    fun cancellingTheCallerWhileTheFirstDocumentIsBlockedSubmitsNoMore() = runBlocking {
        val publish = startPublish(shouldStop = { false })
        assertTrue(firstDocumentSubmitting.await(5, TimeUnit.SECONDS))

        publish.cancel()
        releaseFirstDocument.countDown()
        publish.join()

        assertTrue(publish.isCancelled)
        assertEquals(listOf("doc 1"), submitted)
    }

    @Test
    fun withNoResetEveryDocumentIsSubmittedAndProgressForwarded() = runBlocking {
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        releaseFirstDocument.countDown()

        startPublish(shouldStop = { false }, progress).await()

        assertEquals(documents, submitted)
        assertEquals(listOf(0, 10, 10, 43, 76, 100), progress)
    }
}
