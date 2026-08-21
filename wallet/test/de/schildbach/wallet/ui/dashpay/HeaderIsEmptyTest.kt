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

import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.IdentityCreationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [headerIsEmpty] — the rule behind
 * `HistoryHeaderAdapter.isEmpty()`, which `WalletTransactionsFragment` consults
 * to choose between the "no transactions" view and the transaction list.
 *
 * These pin WHICH inputs can flip that answer, because
 * `WalletTransactionsFragment.showEmptyView()` hides the whole list — header
 * included — and the choice is otherwise only re-taken on a paging load-state
 * emission. Every input proven to flip it here needs its observer to re-take
 * the decision; commit 660156560 did that for sync and canJoin, and the
 * identity record is the remaining one.
 */
class HeaderIsEmptyTest {

    private fun record(
        creationState: IdentityCreationState,
        creationStateErrorMessage: String? = null,
        restoring: Boolean = false,
        usernameSecondary: String? = null
    ) = BlockchainIdentityBaseData(
        creationState = creationState,
        creationStateErrorMessage = creationStateErrorMessage,
        username = "someone",
        usernameSecondary = usernameSecondary,
        userId = "Dpm",
        restoring = restoring
    )

    private fun isEmpty(
        identity: BlockchainIdentityBaseData?,
        hasInvitation: Boolean = false,
        canJoinDashPay: Boolean = false,
        isSynced: Boolean = true,
        hideJoinDashPayCard: Boolean = false,
        votingDualDismissed: Boolean = false
    ) = headerIsEmpty(
        identity = identity,
        hasInvitation = hasInvitation,
        canJoinDashPay = canJoinDashPay,
        isSynced = isSynced,
        hideJoinDashPayCard = hideJoinDashPayCard,
        votingDualDismissed = votingDualDismissed
    )

    @Test
    fun `nothing to show at all`() {
        assertTrue(isEmpty(identity = null))
        assertTrue(isEmpty(record(IdentityCreationState.DONE_AND_DISMISS)))
    }

    @Test
    fun `a creation starting fills a header that was empty`() {
        // THE regression this guards: the identity record is the input, and the
        // fragment's observer for it must re-take the empty-view decision or the
        // processing tile renders into a hidden list.
        assertTrue(isEmpty(record(IdentityCreationState.NONE)))
        assertFalse(isEmpty(record(IdentityCreationState.IDENTITY_REGISTERING)))
    }

    @Test
    fun `a creation error fills a header that was empty`() {
        assertFalse(
            isEmpty(record(IdentityCreationState.USERNAME_REGISTERING, creationStateErrorMessage = "boom"))
        )
    }

    @Test
    fun `a finished creation fills the header until it is dismissed`() {
        assertFalse(isEmpty(record(IdentityCreationState.DONE)))
        assertTrue(isEmpty(record(IdentityCreationState.DONE_AND_DISMISS)))
    }

    @Test
    fun `a dual creation in voting fills the header until its own dismissal`() {
        val dual = record(IdentityCreationState.VOTING, usernameSecondary = "instant")
        assertFalse(isEmpty(dual))
        assertTrue(isEmpty(dual, votingDualDismissed = true))
    }

    @Test
    fun `an invitation fills the header`() {
        assertFalse(isEmpty(record(IdentityCreationState.NONE), hasInvitation = true))
        // Once creation starts the invitation row hands over to the processing
        // tile, so the header stays non-empty across the handover.
        assertFalse(isEmpty(record(IdentityCreationState.CREDIT_FUNDING_TX_SENT), hasInvitation = true))
    }

    @Test
    fun `the invitation row itself yields to a creation in progress`() {
        assertTrue(acceptInvitationEligible(hasInvitation = true, creationInProgress = false))
        assertFalse(acceptInvitationEligible(hasInvitation = true, creationInProgress = true))
        assertFalse(acceptInvitationEligible(hasInvitation = false, creationInProgress = false))
        // No identity record observed yet — does not count towards a non-empty
        // header, matching the rule this replaced.
        assertFalse(acceptInvitationEligible(hasInvitation = true, creationInProgress = null))
    }

    @Test
    fun `sync completing fills the header via the join tile`() {
        // Already covered by the sync observer's re-take (commit 660156560);
        // asserted here so the set of flipping inputs stays complete.
        val fresh = record(IdentityCreationState.NONE)
        assertTrue(isEmpty(fresh, canJoinDashPay = true, isSynced = false))
        assertFalse(isEmpty(fresh, canJoinDashPay = true, isSynced = true))
    }

    @Test
    fun `a dismissed join tile leaves the header empty`() {
        assertTrue(
            isEmpty(
                record(IdentityCreationState.NONE),
                canJoinDashPay = true,
                hideJoinDashPayCard = true
            )
        )
    }
}
