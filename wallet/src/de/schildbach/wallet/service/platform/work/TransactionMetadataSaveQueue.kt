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
import kotlinx.coroutines.CoroutineScope
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
 */
@Singleton
class TransactionMetadataSaveQueue @Inject constructor(applicationScope: CoroutineScope) {
    companion object {
        private val log = LoggerFactory.getLogger(TransactionMetadataSaveQueue::class.java)
    }

    private val saves = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        applicationScope.launch {
            for (save in saves) {
                try {
                    save()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // one failed save must not stop the ones queued behind it
                    log.error("transaction metadata save failed", e)
                }
            }
        }
    }

    /** Queues [save]; the order of calls is the order the saves run in. */
    fun submit(save: suspend () -> Unit) {
        saves.trySend(save)
    }
}
