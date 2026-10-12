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

package de.schildbach.wallet.util

import org.bitcoinj.wallet.WalletFiles
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Numbers the saves of the app's dashj wallet file, so a change made to the
 * in-memory wallet can tell when it has reached disk.
 *
 * Every save takes the next number when it starts ([saveStarting]) and reports
 * it once the file is in place ([saveCompleted]). A change made in memory
 * reads [lastStarted] AFTER making it; a save numbered higher started later,
 * serialized the wallet after the change (dashj takes the wallet's locks to
 * serialize), so its completion means the change is on disk. A save that
 * started earlier may have missed it and does not count.
 *
 * Saves that are not reported only delay that answer — they never give a
 * wrong one.
 */
class WalletSaveTracker {
    private val started = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val walletChangedListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The number of the most recently started save; 0 before the first. */
    fun lastStarted(): Long = started.get()

    /** Call right before the wallet is serialized; returns this save's number. */
    fun saveStarting(): Long = started.incrementAndGet()

    /** Call once save [seq]'s file is in place. Never throws. */
    fun saveCompleted(seq: Long) {
        listeners.forEach { listener ->
            try {
                listener(seq)
            } catch (e: Exception) {
                log.warn("wallet save listener failed", e)
            }
        }
    }

    /** [listener] is told the number of each completed save, on the saving thread. */
    fun addListener(listener: (Long) -> Unit) {
        listeners += listener
    }

    /**
     * The app's wallet was replaced or removed (wipe, restore, recovery from
     * the key backup). State made durable in the previous wallet file is not
     * in the next one, and a change made to the previous wallet never reaches
     * disk through a save of the next one, so listeners drop what they relied
     * on. Call it outside any lock a listener could wait for. Never throws.
     */
    fun walletChanged() {
        walletChangedListeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                log.warn("wallet change listener failed", e)
            }
        }
    }

    /** [listener] is told of each [walletChanged], on the calling thread. */
    fun addWalletChangedListener(listener: () -> Unit) {
        walletChangedListeners += listener
    }

    /** Reports each dashj autosave of the wallet ([org.bitcoinj.wallet.Wallet.autosaveToFile]). */
    fun autosaveListener(): WalletFiles.Listener = object : WalletFiles.Listener {
        // WalletFiles calls both on the saving thread; saves on different
        // threads (autosave and an explicit saveNow) can overlap.
        private val inFlight = ThreadLocal<Long>()

        override fun onBeforeAutoSave(tempFile: File) {
            inFlight.set(saveStarting())
        }

        override fun onAfterAutoSave(newlySavedFile: File) {
            val seq = inFlight.get() ?: return
            inFlight.remove()
            saveCompleted(seq)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WalletSaveTracker::class.java)

        /** The app's wallet ([de.schildbach.wallet.WalletApplication]). */
        @JvmField
        val APP = WalletSaveTracker()
    }
}
