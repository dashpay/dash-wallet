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

import de.schildbach.wallet.payments.FundingSelectionLock
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
 *    depend on a fragment/activity that will not survive the wipe. A marker
 *    that is not confirmed refuses the reset: no hand-off, nothing torn down.
 *  - [finish] does the destroying, and only if [begin] confirmed the wipe or
 *    the marker says one is pending.
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
     * FAILS CLOSED: unless the marker is positively confirmed (written and
     * read back), the reset is refused — the UI is not handed off, the payment
     * barrier is not raised, and the caller must tell the user Reset Wallet
     * failed. Handing off without a marker used to leave the old wallet
     * installed and intact behind onboarding: [finish] found no marker, took
     * the no-wipe branch and destroyed nothing.
     *
     * @param markPending persists the "wipe started" marker; true only when
     *   it is confirmed on disk. A throw counts as false.
     * @param fundingBarrier raised once the marker is confirmed, before the
     *   hand-off: from then on no payment may fund, broadcast or post for this
     *   wallet. Not touched on a refusal, so a refused reset cancels no payment
     *   and leaves the barrier as it was (down, unless a wipe already raised it).
     * @param handOffUi moves the UI off the wallet (onboarding, task cleared)
     * @return true when the wipe was recorded and handed off; pass it to
     *   [finish] as `beginConfirmed`.
     */
    fun begin(
        markPending: () -> Boolean,
        fundingBarrier: FundingSelectionLock,
        handOffUi: () -> Unit
    ): Boolean {
        val confirmed = try {
            markPending()
        } catch (t: Throwable) {
            log.error("could not record the wipe marker", t)
            false
        }
        if (!confirmed) {
            log.error("Reset Wallet refused: the wipe marker could not be confirmed — nothing torn down, wallet kept")
            return false
        }
        fundingBarrier.raiseWipeBarrier()
        handOffUi()
        return true
    }

    /**
     * Phase 3-5, in a coroutine.
     *
     * @param beginConfirmed what [begin] returned in THIS process for the wipe
     *   being finished; false when there is none in hand (a wipe resumed at
     *   launch, a wipe command from anywhere else). A confirmed begin is the
     *   user's request with its marker verified on disk: the teardown proceeds
     *   whatever the marker reads now.
     * @param pending what the marker says now. Without a confirmed begin:
     *   [WalletWipeState.Pending.PENDING] proceeds;
     *   [WalletWipeState.Pending.NOT_PENDING] means nothing asked for a wipe —
     *   destroying a wallet nobody asked to destroy is the one unrecoverable
     *   mistake this path can make — so nothing is destroyed and the barrier
     *   is lowered; [WalletWipeState.Pending.UNKNOWN] is neither: nothing is
     *   destroyed, the barrier STAYS UP and the marker stays for the next
     *   launch to classify (it may be an unverified marker that requires
     *   manual recovery). A throw counts as UNKNOWN.
     * @param fundingBarrier the payment paths' wipe barrier. Raised (again:
     *   Reset Wallet raised it when the user confirmed) and DRAINED before
     *   [detachWallet]: a payment's SDK funding or broadcast, or a BIP70 POST,
     *   admitted before the wipe finishes before anything is detached or torn
     *   down. Held through [destroy] (the payment verifier's own clear runs
     *   inside it) and lowered only after [markComplete], once the old wallet
     *   is gone. A wipe that throws leaves it up: the payment paths stay
     *   refused for the rest of this half-wiped process, and the next launch
     *   finishes the wipe. Lowered without a wipe only on a confirmed
     *   NOT_PENDING.
     * @param detachWallet the in-memory wallet leaves the app. Before, not
     *   after, the data behind it is deleted.
     * @param destroy the files, keystore secrets, preferences and databases.
     * @param markComplete clears the marker. Runs ONLY if [destroy] returned:
     *   a throw leaves the marker on disk so the next launch re-runs the wipe
     *   instead of coming up half-wiped.
     * @return true when the wipe ran.
     */
    suspend fun finish(
        beginConfirmed: Boolean = false,
        pending: () -> WalletWipeState.Pending,
        fundingBarrier: FundingSelectionLock,
        detachWallet: suspend () -> Unit,
        destroy: suspend () -> Unit,
        markComplete: () -> Unit
    ): Boolean {
        val marker = try {
            pending()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("could not read the wipe marker", t)
            WalletWipeState.Pending.UNKNOWN
        }
        if (beginConfirmed) {
            if (marker != WalletWipeState.Pending.PENDING) {
                log.warn("wipe marker reads {} after a confirmed Reset Wallet — wiping as the user asked", marker)
            }
        } else {
            when (marker) {
                WalletWipeState.Pending.PENDING -> Unit
                WalletWipeState.Pending.NOT_PENDING -> {
                    log.warn("wipe teardown reached without a wipe having been started — destroying nothing")
                    // Nothing is destroyed, so the wallet a barrier raised for this stop protects is
                    // still the installed one.
                    fundingBarrier.lowerWipeBarrier()
                    return false
                }
                WalletWipeState.Pending.UNKNOWN -> {
                    log.error("wipe marker state unknown — destroying nothing, payments stay refused, marker kept for the next launch")
                    fundingBarrier.raiseWipeBarrier()
                    return false
                }
            }
        }
        // First: a wipe resumed at launch did not come through Reset Wallet's raise.
        fundingBarrier.raiseWipeBarrier()
        fundingBarrier.awaitWipeDrained()
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
        fundingBarrier.lowerWipeBarrier()
        return true
    }
}
