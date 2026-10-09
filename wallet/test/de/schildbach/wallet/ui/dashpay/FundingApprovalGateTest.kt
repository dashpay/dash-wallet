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
package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.database.entity.IdentityCreationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for [needsFreshFundingApproval] — the gate
 * [CreateIdentityService.createIdentity] checks immediately before
 * constructing NEW funding (a fresh asset lock, or a top-up), not at the
 * resume entry point (MO-1069 review 5462459067: the prior fix refused
 * EVERY resume with a missing approval, including already-funded
 * registration-stage records with nothing left to approve).
 */
class FundingApprovalGateTest {

    @Test
    fun olderAlreadyFundedRecord_resumesWithoutApproval() {
        // PREORDER_REGISTERING / USERNAME_REGISTERING are well past
        // CREDIT_FUNDING_TX_CREATING — their asset lock is already built and
        // confirmed, so a record created before approvedFundingAmountDuffs
        // existed (null) must still be allowed to resume.
        assertFalse(
            needsFreshFundingApproval(IdentityCreationState.PREORDER_REGISTERING, null)
        )
        assertFalse(
            needsFreshFundingApproval(IdentityCreationState.USERNAME_REGISTERING, null)
        )
        assertFalse(
            needsFreshFundingApproval(IdentityCreationState.IDENTITY_REGISTERED, null)
        )
    }

    @Test
    fun olderRecordStillNeedingFunding_asksForConfirmation() {
        // At or before CREDIT_FUNDING_TX_CREATING, a fresh asset lock or
        // top-up is still about to be constructed — a missing approval here
        // means there is a real new spend decision with nothing to cap it.
        assertTrue(
            needsFreshFundingApproval(IdentityCreationState.NONE, null)
        )
        assertTrue(
            needsFreshFundingApproval(IdentityCreationState.UPGRADING_WALLET, null)
        )
        assertTrue(
            needsFreshFundingApproval(IdentityCreationState.CREDIT_FUNDING_TX_CREATING, null)
        )
    }

    @Test
    fun aValidApproval_neverRefuses_regardlessOfState() {
        assertFalse(
            needsFreshFundingApproval(IdentityCreationState.NONE, 25_000_000L)
        )
        assertFalse(
            needsFreshFundingApproval(IdentityCreationState.CREDIT_FUNDING_TX_CREATING, 25_000_000L)
        )
        assertFalse(
            needsFreshFundingApproval(IdentityCreationState.PREORDER_REGISTERING, 25_000_000L)
        )
    }
}
