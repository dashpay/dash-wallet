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

package de.schildbach.wallet.payments

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A shared/exclusive lock over fresh SDK coin selection and a resubmission's reactivation
 * (reviews 4309551218, and the follow-up on its length).
 *
 * The SDK engine cannot see the dashj locks that protect an uncertain payment, so fresh SDK
 * funding is authorized by the funding gate ([PendingPaymentFundingGate]) instead. A resubmission
 * makes a released payment active again ([PendingDirectPaymentVerifier.reactivateForResubmission]),
 * which flips that gate; without coordination a funding call authorized just before could still
 * select the payment's inputs just after.
 *
 * - Fresh funding holds it SHARED ([withShared], [selectIfAllowed]): it asks the gate again under
 *   the hold and keeps it through its SDK call (selection and reservation). Fundings never wait
 *   for one another, so a send is not held up by an identity registration that waits minutes for
 *   its asset lock.
 * - The reactivation takes it EXCLUSIVE and fail-fast ([tryExclusive]): refused at once while any
 *   funding holds it, and otherwise short, so a funding only ever waits for that short section.
 * - Settling an invalidated payment holds it SHARED, so it never interleaves with a reactivation.
 * - A wallet wipe takes it EXCLUSIVE and waits ([exclusiveForWipe]): it waits for the holds under
 *   way to end, blocks new ones while it clears the old wallet's payment state, and every hold
 *   that was already waiting when it began is then refused ([WalletWipedException]) rather than
 *   let through onto whatever wallet is installed next. The wipe never waits, under its hold, for
 *   anything that itself needs a hold: what it joins is cancelled first, and a coroutine waiting
 *   for a hold gives up at once when cancelled.
 *
 * Outermost: taken before the verifier's scanMutex, recordMutex, settleMutex and claimMutex,
 * never while holding any of them. Not reentrant for the exclusive side.
 */
@Singleton
class FundingSelectionLock @Inject constructor() {
    companion object {
        private val log = LoggerFactory.getLogger(FundingSelectionLock::class.java)

        /** How long a wipe waits for the holds under way before going ahead without them. */
        const val WIPE_DRAIN_TIMEOUT_MS = 30_000L
    }

    /** Held through an exclusive section; a shared hold passes through it to register itself. */
    private val exclusive = Mutex()
    private val sharedHolders = MutableStateFlow(0)

    /** Bumped by every wipe; a shared hold that began waiting before one is refused. */
    private val wipes = AtomicLong(0)

    /**
     * [block] under a shared hold; waits only for an exclusive section that is under way.
     *
     * @throws WalletWipedException when a wallet wipe ran while this waited for the hold: the
     *   caller decided to fund from a wallet that is gone, and nothing of it is run
     */
    suspend fun <T> withShared(block: suspend () -> T): T {
        val wipesBefore = wipes.get()
        exclusive.withLock {
            if (wipes.get() != wipesBefore) {
                throw WalletWipedException()
            }
            sharedHolders.update { it + 1 }
        }
        try {
            return block()
        } finally {
            sharedHolders.update { it - 1 }
        }
    }

    /** [block] under the exclusive hold, or null at once when it cannot be had. */
    suspend fun <T> tryExclusive(block: suspend () -> T): T? {
        if (!exclusive.tryLock()) return null
        try {
            // no shared hold can register while this holds [exclusive]
            if (sharedHolders.value > 0) return null
            return block()
        } finally {
            exclusive.unlock()
        }
    }

    /**
     * [block] under the exclusive hold, for a wallet wipe: waits for the exclusive side, then for
     * every shared hold under way to end (at most [drainTimeoutMs]; a hold wedged past that must
     * not stop a wipe, which then goes ahead with new holds still blocked), and refuses every
     * shared hold asked for while the wipe ran ([withShared]).
     *
     * [block] must not wait for anything that needs a hold of this lock.
     */
    suspend fun <T> exclusiveForWipe(drainTimeoutMs: Long = WIPE_DRAIN_TIMEOUT_MS, block: suspend () -> T): T {
        // Counted at the start and again at the end, so a hold asked for at any point while the
        // wipe runs finds the count changed once it gets in.
        wipes.incrementAndGet()
        try {
            return exclusive.withLock {
                val drained = withTimeoutOrNull(drainTimeoutMs) { sharedHolders.first { it == 0 } } != null
                if (!drained) {
                    log.warn("a funding selection is still under way after {} ms; the wipe goes ahead", drainTimeoutMs)
                }
                block()
            }
        } finally {
            wipes.incrementAndGet()
        }
    }

    /**
     * [select] under a shared hold once [blocked] (the funding gate, asked again here) says it may
     * select fresh inputs; null, selecting nothing, when it may not, or when a wallet wipe ran
     * while it waited for the hold.
     */
    suspend fun <T> selectIfAllowed(blocked: suspend () -> Boolean, select: suspend () -> T): Selected<T>? =
        try {
            withShared { if (blocked()) null else Selected(select()) }
        } catch (e: WalletWipedException) {
            null
        }

    /** What [selectIfAllowed] selected. */
    class Selected<T>(val value: T)
}

/** A funding selection refused because the wallet it was for was wiped while it waited. */
class WalletWipedException :
    IllegalStateException("the wallet was wiped while this payment waited to select its inputs; nothing was selected")
