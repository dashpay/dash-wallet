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

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
 * calls [discardPending] before it clears anything.
 */
@Singleton
class TransactionMetadataSaveQueue @Inject constructor(private val applicationScope: CoroutineScope) {
    companion object {
        private val log = LoggerFactory.getLogger(TransactionMetadataSaveQueue::class.java)
    }

    private class Save(val generation: Long, val block: suspend () -> Unit)

    private val saves = Channel<Save>(Channel.UNLIMITED)
    private val lock = Any()
    /** bumped by [discardPending]; a save from an older generation never runs */
    private var generation = 0L
    private var running: Job? = null

    // A save that throws is logged and the queue moves on to the next one.
    private val failureHandler = CoroutineExceptionHandler { _, e ->
        log.error("transaction metadata save failed", e)
    }

    init {
        applicationScope.launch {
            for (save in saves) {
                // Each save is its own job, a sibling of this consumer rather
                // than a child, so a save that fails or is cancelled ends only
                // itself. join() throws only when the consumer itself is
                // cancelled — then the queue is going away anyway.
                val job = synchronized(lock) {
                    if (save.generation != generation) {
                        null
                    } else {
                        applicationScope.launch(failureHandler) { save.block() }.also { running = it }
                    }
                } ?: continue
                job.join()
            }
        }
    }

    /** Queues [save]; the order of calls is the order the saves run in. */
    fun submit(save: suspend () -> Unit) {
        synchronized(lock) { saves.trySend(Save(generation, save)) }
    }

    /**
     * Drops every queued save and cancels the one running, returning once it
     * has stopped. Saves submitted afterwards run normally.
     */
    suspend fun discardPending() {
        val inFlight = synchronized(lock) {
            generation++
            running
        }
        inFlight?.cancelAndJoin()
    }
}
