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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One lock over fresh SDK coin selection and a resubmission's reactivation (review 4309551218).
 *
 * The SDK engine cannot see the dashj locks that protect an uncertain payment, so fresh SDK
 * funding is authorized by the funding gate ([PendingPaymentFundingGate]) instead. A resubmission
 * makes a released payment active again ([PendingDirectPaymentVerifier.reactivateForResubmission]),
 * which flips that gate; without a shared lock a funding call authorized just before could still
 * select the payment's inputs just after. Every fresh-funding entry point re-asks the gate under
 * this lock and holds it through its SDK call (selection and reservation); the reactivation holds
 * it too.
 *
 * Outermost lock: taken before the verifier's scanMutex, recordMutex, settleMutex and claimMutex,
 * never while holding any of them. Not reentrant: a funding call under it must not reach another
 * gated entry point.
 */
@Singleton
class FundingSelectionLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withSelection(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * [select] under the lock once [blocked] (the funding gate, asked again here) says it may
     * select fresh inputs; null, selecting nothing, when it may not.
     */
    suspend fun <T> selectIfAllowed(blocked: suspend () -> Boolean, select: suspend () -> T): Selected<T>? =
        mutex.withLock { if (blocked()) null else Selected(select()) }

    /** What [selectIfAllowed] selected. */
    class Selected<T>(val value: T)
}
