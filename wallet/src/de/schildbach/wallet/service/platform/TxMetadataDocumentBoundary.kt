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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A metadata publish stopped between documents because [shouldStop] said so. */
class TxMetadataPublishStoppedException : CancellationException("tx metadata publish stopped between documents")

/**
 * Runs a blocking SDK metadata publish so it can be stopped between documents.
 *
 * `BlockchainIdentity.publishTxMetaData` is not suspending: it submits its
 * documents one by one on the calling thread, so cancelling the caller's
 * coroutine cannot interrupt it, and a check made after it returns comes too
 * late to keep later documents off the network. The one hook it offers is its
 * progress callback, which it calls synchronously once before the first
 * document and once after each — so the callback is where this checks, and
 * throwing from it ends the SDK loop before the next document is submitted.
 *
 * @param shouldStop checked before each document, e.g. "a wallet reset is under way"
 * @param onProgress forwarded the SDK's progress while the publish continues
 * @param publish the SDK call, given the progress callback to hand to the SDK
 * @throws CancellationException if the caller's coroutine is cancelled, or
 *   [TxMetadataPublishStoppedException] if [shouldStop] returns true, at the
 *   next document boundary
 */
internal suspend fun <T> publishStoppingBetweenDocuments(
    shouldStop: () -> Boolean,
    onProgress: (Int) -> Unit,
    publish: (progressListener: (Int) -> Unit) -> T
): T {
    // the caller's own job, so cancelling the caller (e.g. the publish
    // worker, or a timeout around the call) reaches the SDK's loop
    val job = currentCoroutineContext()[Job]
    return publish { progress ->
        job?.ensureActive()
        if (shouldStop()) {
            throw TxMetadataPublishStoppedException()
        }
        onProgress(progress)
    }
}
