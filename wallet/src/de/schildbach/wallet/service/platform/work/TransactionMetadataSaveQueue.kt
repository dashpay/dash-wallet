/*
 * Copyright 2026 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs transaction-metadata settings saves one at a time, in submission order,
 * in the application scope.
 *
 * The settings screen submits a save and closes, so a save outlives the screen
 * that asked for it. Reopen the screen and save again before the first one has
 * finished its DataStore writes, and two saves are in flight at once: without
 * ordering, the older one can land last and overwrite the newer settings and
 * the stored last work id.
 *
 * Because a queued save carries one wallet's consent choices, Reset Wallet
 * [pause]s the queue the moment it is confirmed, [pauseAndJoin]s before it
 * clears anything, and [resume]s once the old wallet is gone.
 */
@Singleton
class TransactionMetadataSaveQueue @Inject constructor(private val applicationScope: CoroutineScope) {
    companion object {
        private val log = LoggerFactory.getLogger(TransactionMetadataSaveQueue::class.java)
    }

    /** A save was refused, dropped or stopped because the queue was paused. */
    class SaveDiscardedException : CancellationException("transaction metadata save discarded")

    /** The receiver of every save. */
    inner class SaveScope internal constructor(private val generation: Long) {
        /**
         * Runs [step], the save's final side effect (enqueuing a publish), only
         * if the queue has not been paused since the save was submitted — and
         * atomically with [pause], so once [pause] returns no step from before
         * it can run. Cancellation alone cannot promise that: [step] has no
         * suspension point to be cancelled at.
         *
         * @return the step's result, or null if it was skipped
         */
        fun <T> commit(step: () -> T): T? = synchronized(lock) {
            if (isCurrent(generation)) step() else null
        }
    }

    private class Save(
        val generation: Long,
        val block: suspend SaveScope.() -> Unit,
        /** called with the failure, or null when the save is dropped unrun */
        val onEnd: (Throwable?) -> Unit = {}
    )

    private val saves = Channel<Save>(Channel.UNLIMITED)
    private val lock = Any()
    /** bumped by [pause]; a save from an older generation never runs or commits */
    private var generation = 0L
    private var paused = false
    private var running: Job? = null

    // A save that throws is logged and the queue moves on to the next one.
    private val failureHandler = CoroutineExceptionHandler { _, e ->
        log.error("transaction metadata save failed", e)
    }

    /** Call with [lock] held. */
    private fun isCurrent(saveGeneration: Long) = !paused && saveGeneration == generation

    init {
        applicationScope.launch {
            for (save in saves) {
                // Each save is its own job, a sibling of this consumer rather
                // than a child, so a save that fails or is cancelled ends only
                // itself. join() throws only when the consumer itself is
                // cancelled — then the queue is going away anyway.
                val job = synchronized(lock) {
                    if (isCurrent(save.generation)) {
                        applicationScope.launch(failureHandler) { SaveScope(save.generation).(save.block)() }
                            .also { running = it }
                    } else {
                        null
                    }
                }
                if (job == null) {
                    save.onEnd(null)
                    continue
                }
                job.invokeOnCompletion(save.onEnd)
                job.join()
            }
        }
    }

    /**
     * Queues [save]; the order of calls is the order the saves run in.
     *
     * @return false if the queue is paused and the save was refused
     */
    fun submit(save: suspend SaveScope.() -> Unit): Boolean = enqueue(Save(currentGeneration(), save))

    /**
     * Queues [save] like [submit] and waits for its result. Cancelling the
     * caller stops only the wait; the save runs on.
     *
     * @throws SaveDiscardedException if the save was refused, dropped or
     *   stopped because the queue was paused
     */
    suspend fun <T> submitAndAwait(save: suspend SaveScope.() -> T): T {
        val result = CompletableDeferred<T>()
        val queued = enqueue(
            Save(
                currentGeneration(),
                block = { result.complete(save()) },
                onEnd = { cause ->
                    if (!result.isCompleted) {
                        result.completeExceptionally(
                            if (cause == null || cause is CancellationException) SaveDiscardedException() else cause
                        )
                    }
                }
            )
        )
        if (!queued) throw SaveDiscardedException()
        return result.await()
    }

    /**
     * Drops every queued save, cancels the one running, makes sure none of them
     * can [SaveScope.commit], and refuses new saves until [resume]. Does not
     * wait for the cancelled save to stop — for callers that cannot suspend.
     *
     * @return the cancelled save, still winding down, or null if none ran
     */
    fun pause(): Job? = synchronized(lock) {
        paused = true
        generation++
        running?.also { it.cancel() }
    }

    /** [pause], returning once the cancelled save has stopped. */
    suspend fun pauseAndJoin() {
        pause()?.join()
    }

    /** Accepts saves again after [pause]. */
    fun resume() {
        synchronized(lock) { paused = false }
    }

    private fun currentGeneration() = synchronized(lock) { generation }

    private fun enqueue(save: Save): Boolean = synchronized(lock) {
        if (paused || save.generation != generation) {
            log.info("transaction metadata save refused: the queue is paused")
            false
        } else {
            saves.trySend(save).isSuccess
        }
    }
}
