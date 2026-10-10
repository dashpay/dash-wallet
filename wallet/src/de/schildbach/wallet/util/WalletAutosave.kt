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

import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletFiles
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the dashj wallet's autosave (`Wallet.autosaveToFile`) for
 * [de.schildbach.wallet.WalletApplication]: arms it, stops it, and switches
 * its debounce at runtime when the Tools › "dashj sync (diagnostic)" engine
 * starts or stops.
 *
 * ## Why the diagnostic gets its own debounce
 *
 * In the diagnostic the full dashj engine re-syncs alongside the SDK, and
 * dashj re-serialises the WHOLE wallet once per debounce window while blocks
 * arrive. On the reference tester wallet (61.6 MB file, 33k transactions, 512
 * MB heap) the size tier gives 60 s: 256 full saves in one day, each a
 * multi-second (p90 27 s) heap peak on top of a ~253 MB resident wallet, and
 * all four OOM episodes happened mid-save. Every one of those saves wrote the
 * same 61,642,388 bytes: only the last-seen block moved. See
 * [DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS].
 *
 * ## Switching at runtime, safely
 *
 * dashj's [WalletFiles] fixes its delay at construction, so a new debounce
 * means a new [WalletFiles]. [reconcile] swaps it without ever having two
 * autosavers and without losing the save the old one had queued:
 *
 * 1. `WalletFiles.shutdownAndWait()` on the OLD manager, WITHOUT the wallet
 *    lock: a save already running finishes (it takes the wallet lock itself),
 *    a save still waiting out its delay is cancelled (dashj sets
 *    `executeExistingDelayedTasksAfterShutdown=false`). dashj's own
 *    `Wallet.shutdownAutosaveAndWait()` waits while HOLDING the wallet write
 *    lock, which deadlocks against a save that has started but not yet taken
 *    that lock; stopping the executor first removes that window.
 * 2. `Wallet.shutdownAutosaveAndWait()` detaches it; its executor has already
 *    terminated, so this returns at once.
 * 3. `Wallet.autosaveToFile()` arms the new delay (dashj refuses a second
 *    manager, so there is never more than one).
 * 4. `WalletFiles.saveLater()` on the new manager re-queues the save step 1
 *    may have cancelled. It writes the whole wallet as of when it fires, so it
 *    also covers a save request that arrived between steps 1 and 3 (dashj
 *    drops those silently while no manager is live).
 *
 * The cost is at most one save per switch, in place of the one the switch
 * cancelled. The switch only happens when the diagnostic mode starts or ends.
 *
 * Every arm, stop and switch is serialised on one lock. The requested mode is
 * written before that lock is taken and read inside it, so concurrent requests
 * end in the LAST one requested, whichever thread reconciles last.
 *
 * Must not be called on the main thread: [reconcile] and [shutdown] wait for a
 * save in flight.
 */
class WalletAutosave {

    companion object {
        /**
         * Debounce while the dashj engine runs only for the Tools diagnostic
         * (cutover committed + toggle on): 10 minutes. The diagnostic compares
         * dashj's balance and transactions with the SDK's; it does not need
         * dashj's progress on disk every minute, and a full save of a large
         * wallet is the largest heap peak this mode has (see the class KDoc).
         * dashj coalesces every change inside the window into one write, so
         * this cuts block-download saves ten-fold against the 60 s size tier.
         * Saves dashj makes immediately (new keys, a sent transaction) and the
         * app's explicit saves (service shutdown) are not affected.
         *
         * The trade-off: a process death loses up to this much dashj progress,
         * which dashj then re-downloads.
         */
        const val DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS = 10L * 60L * 1000L

        /**
         * The debounce to arm: the size tier from
         * [WalletFileSizeGuard.autosaveDelayMs], raised to
         * [DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS] while the dashj engine runs only
         * for the diagnostic. Every other mode keeps the size tier untouched.
         */
        @JvmStatic
        fun autosaveDelayMs(sizeTierDelayMs: Long, dashjDiagnosticSync: Boolean): Long =
            if (dashjDiagnosticSync) maxOf(sizeTierDelayMs, DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS) else sizeTierDelayMs

        private val log = LoggerFactory.getLogger(WalletAutosave::class.java)
    }

    private val lock = Any()

    @Volatile
    private var dashjDiagnosticRequested = false

    // All below guarded by [lock].
    private var armedWallet: Wallet? = null
    private var armedFile: File? = null
    private var files: WalletFiles? = null
    private var sizeTierDelayMs = 0L
    private var armedDelayMs = 0L

    /** The debounce currently armed, or null when no autosave is armed. */
    val armedDelayMillis: Long?
        get() = synchronized(lock) { if (files != null) armedDelayMs else null }

    /**
     * Arms autosave of [wallet] to [walletFile] with [sizeTierDelayMs], raised for the
     * diagnostic when it is already requested. Same contract as
     * `Wallet.autosaveToFile`: throws if [wallet] already autosaves.
     *
     * @return the debounce armed.
     */
    fun arm(wallet: Wallet, walletFile: File, sizeTierDelayMs: Long): Long = synchronized(lock) {
        val delayMs = autosaveDelayMs(sizeTierDelayMs, dashjDiagnosticRequested)
        val armed = wallet.autosaveToFile(walletFile, delayMs, TimeUnit.MILLISECONDS, null)
        armedWallet = wallet
        armedFile = walletFile
        files = armed
        this.sizeTierDelayMs = sizeTierDelayMs
        armedDelayMs = delayMs
        if (delayMs != sizeTierDelayMs) {
            log.info("wallet autosave debounce armed at {} ms for the dashj sync diagnostic", delayMs)
        }
        delayMs
    }

    /**
     * Stops [wallet]'s autosave as `Wallet.shutdownAutosaveAndWait()` does
     * (and throws as it does when none is armed), and forgets it when it is
     * the one [arm] armed. For that one the executor is stopped first, without
     * the wallet lock, as in [reconcile] step 1; the outcome is the same (a
     * running save finishes, a delayed one is dropped), minus the deadlock
     * window.
     */
    fun shutdown(wallet: Wallet): Unit = synchronized(lock) {
        try {
            if (wallet === armedWallet) files?.let { shutdownUninterruptibly(it) }
            wallet.shutdownAutosaveAndWait()
        } finally {
            if (wallet === armedWallet) {
                armedWallet = null
                armedFile = null
                files = null
            }
        }
    }

    /**
     * Records whether the dashj engine now runs only for the diagnostic. Cheap
     * and non-blocking; [reconcile] applies it.
     */
    fun requestDashjDiagnostic(enabled: Boolean) {
        dashjDiagnosticRequested = enabled
    }

    /**
     * Re-arms the armed wallet's autosave if the requested mode changes its
     * debounce; a no-op otherwise, or when nothing is armed yet ([arm] picks
     * the request up). Blocks while a save in flight finishes.
     *
     * @return true when the autosave was re-armed.
     */
    fun reconcile(): Boolean = synchronized(lock) {
        val wallet = armedWallet ?: return false
        val walletFile = armedFile ?: return false
        val current = files ?: return false
        val diagnostic = dashjDiagnosticRequested
        val wantedMs = autosaveDelayMs(sizeTierDelayMs, diagnostic)
        if (wantedMs == armedDelayMs) return false

        log.info(
            "wallet autosave debounce {} ms -> {} ms (dashj sync diagnostic {})",
            armedDelayMs, wantedMs, if (diagnostic) "on" else "off"
        )
        shutdownUninterruptibly(current)
        files = null
        wallet.shutdownAutosaveAndWait()
        val rearmed = wallet.autosaveToFile(walletFile, wantedMs, TimeUnit.MILLISECONDS, null)
        files = rearmed
        armedDelayMs = wantedMs
        rearmed.saveLater()
        true
    }

    /**
     * [WalletFiles.shutdownAndWait] waits with no timeout but wraps an
     * interrupt in a RuntimeException after `executor.shutdown()` has already
     * run, which would leave the wallet with a dead autosaver. Wait the
     * shutdown out regardless, then restore the interrupt.
     */
    private fun shutdownUninterruptibly(files: WalletFiles) {
        var interrupted = false
        while (true) {
            try {
                files.shutdownAndWait()
                break
            } catch (e: RuntimeException) {
                if (e.cause !is InterruptedException) throw e
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
