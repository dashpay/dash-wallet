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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPaymentFundingGateTest {

    private fun gate(verifier: PendingDirectPaymentVerifier) = PendingPaymentFundingGate({ verifier }, selectionLock = de.schildbach.wallet.payments.FundingSelectionLock())

    @Test
    fun blocksWhileAPendingPaymentIsUnprotected() = runTest {
        val verifier = mockk<PendingDirectPaymentVerifier> {
            coEvery { hasUnprotectedPendingPayment() } returns true
        }
        assertTrue(gate(verifier).isFreshFundingBlocked())
    }

    @Test
    fun allowsWhenNothingIsUnprotected() = runTest {
        val verifier = mockk<PendingDirectPaymentVerifier> {
            coEvery { hasUnprotectedPendingPayment() } returns false
        }
        assertFalse(gate(verifier).isFreshFundingBlocked())
    }

    @Test
    fun failsClosedWhenTheCheckThrows() = runTest {
        val verifier = mockk<PendingDirectPaymentVerifier> {
            coEvery { hasUnprotectedPendingPayment() } throws IllegalStateException("store unreadable")
        }
        assertTrue(gate(verifier).isFreshFundingBlocked())
    }
}
