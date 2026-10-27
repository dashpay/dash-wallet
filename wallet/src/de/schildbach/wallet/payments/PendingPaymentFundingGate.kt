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

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The one rule every SDK spend that selects FRESH transparent L1 inputs must ask before the
 * native call: is an uncertain BIP70 payment's inputs exposed to the engine's coin selection
 * ([PendingDirectPaymentVerifier.hasUnprotectedPendingPayment])? While it is, the spend is
 * refused with [de.schildbach.wallet.service.platform.sdk.L1_PENDING_PAYMENT_UNPROTECTED_REASON]
 * and nothing is built. Resuming an asset lock the engine already tracks re-consumes that lock's
 * own outputs and is never asked.
 *
 * Holds the verifier through a [Provider]: the verifier reaches the SDK send service through its
 * engine, so a direct edge from the SDK services would be a dependency cycle.
 */
@Singleton
class PendingPaymentFundingGate @Inject constructor(
    private val verifier: Provider<PendingDirectPaymentVerifier>,
    /**
     * The lock a fresh-funding entry point asks this gate again under, and holds through its
     * selection (review 4309551218): shared with the verifier's reactivation.
     */
    val selectionLock: FundingSelectionLock
) {
    companion object {
        private val log = LoggerFactory.getLogger(PendingPaymentFundingGate::class.java)
    }

    /** True while fresh SDK funding must be refused. Fails closed. */
    suspend fun isFreshFundingBlocked(): Boolean = try {
        verifier.get().hasUnprotectedPendingPayment()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.warn("could not check for unprotected pending payments; refusing fresh SDK funding", e)
        true
    }
}
