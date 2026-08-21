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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.util

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * THE ORDER a Reset Wallet must run in, in one place, free of Android so the
 * order itself can be tested.
 *
 * The order is the defect that was reported, not a preference. Reset Wallet
 * used to destroy first and move the UI last: on a mainnet device every
 * destructive step (SDK cleanup, database clear, wallet removed from memory)
 * completed at 22:40:20 while the wallet UI was still up — the auto-logout had
 * even taken the user through the lock screen at 22:39:11 and back into the
 * old wallet's main screen, with the wallet file and the keystore secrets
 * already deleted. That is what the user saw and reported as "it didn't
 * actually reset". The trailing UI hand-off then crashed the process
 * (`IllegalStateException: Fragment SecurityFragment not attached to Activity`
 * — `Fragment.startActivity` from the post-wipe callback, invoked on
 * `DefaultDispatcher-worker-7` two minutes after the fragment was gone).
 *
 * So:
 *  - [begin] runs FIRST and runs to the end before anything is destroyed. It
 *    records the wipe ([WalletWipeState]) and hands the UI off — from that
 *    point the app presents onboarding, not a wallet, and the hand-off cannot
 *    depend on a fragment/activity that will not survive the wipe.
 *  - [finish] does the destroying, and only if [begin] recorded a wipe.
 *
 * Both halves are needed because the two run minutes apart in different
 * places: [begin] on the main thread when the user confirms, [finish] in the
 * blockchain service's teardown coroutine (or on the next launch, when the
 * previous process died mid-wipe).
 */
object WalletWipeSequence {
    private val log = LoggerFactory.getLogger(WalletWipeSequence::class.java)

    /**
     * Phase 1-2, synchronous and before any destruction.
     *
     * @param markPending persists the "wipe started" marker
     * @param handOffUi moves the UI off the wallet (onboarding, task cleared)
     */
    fun begin(markPending: () -> Boolean, handOffUi: () -> Unit) {
        if (!markPending()) {
            // Not fatal: the wipe still runs. Only mid-wipe death recovery is
            // lost, so the failure has to be visible in the log.
            log.warn("wipe started WITHOUT a recovery marker — a process death mid-wipe will not be repaired")
        }
        handOffUi()
    }

    /**
     * Phase 3-5, in a coroutine.
     *
     * @param pending whether [begin] recorded a wipe. A false answer means
     *   nothing asked for a wipe, and destroying a wallet nobody asked to
     *   destroy is the one unrecoverable mistake this path can make.
     * @param detachWallet the in-memory wallet leaves the app. Before, not
     *   after, the data behind it is deleted.
     * @param destroy the files, keystore secrets, preferences and databases.
     * @param markComplete clears the marker. Runs ONLY if [destroy] returned:
     *   a throw leaves the marker on disk so the next launch re-runs the wipe
     *   instead of coming up half-wiped.
     * @return true when the wipe ran.
     */
    suspend fun finish(
        pending: () -> Boolean,
        detachWallet: suspend () -> Unit,
        destroy: suspend () -> Unit,
        markComplete: () -> Unit
    ): Boolean {
        if (!pending()) {
            log.warn("wipe teardown reached without a wipe having been started — destroying nothing")
            return false
        }
        detachWallet()
        try {
            destroy()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.error("wipe destruction failed — leaving the marker so the next launch finishes it", t)
            throw t
        }
        markComplete()
        return true
    }
}
