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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
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
 *
 * Outermost: taken before the verifier's scanMutex, recordMutex, settleMutex and claimMutex,
 * never while holding any of them. Not reentrant for the exclusive side.
 */
@Singleton
class FundingSelectionLock @Inject constructor() {
    /** Held through an exclusive section; a shared hold passes through it to register itself. */
    private val exclusive = Mutex()
    private val sharedHolders = AtomicInteger(0)

    /** [block] under a shared hold; waits only for an exclusive section that is under way. */
    suspend fun <T> withShared(block: suspend () -> T): T {
        exclusive.withLock { sharedHolders.incrementAndGet() }
        try {
            return block()
        } finally {
            sharedHolders.decrementAndGet()
        }
    }

    /** [block] under the exclusive hold, or null at once when it cannot be had. */
    suspend fun <T> tryExclusive(block: suspend () -> T): T? {
        if (!exclusive.tryLock()) return null
        try {
            // no shared hold can register while this holds [exclusive]
            if (sharedHolders.get() > 0) return null
            return block()
        } finally {
            exclusive.unlock()
        }
    }

    /**
     * [select] under a shared hold once [blocked] (the funding gate, asked again here) says it may
     * select fresh inputs; null, selecting nothing, when it may not.
     */
    suspend fun <T> selectIfAllowed(blocked: suspend () -> Boolean, select: suspend () -> T): Selected<T>? =
        withShared { if (blocked()) null else Selected(select()) }

    /** What [selectIfAllowed] selected. */
    class Selected<T>(val value: T)
}
