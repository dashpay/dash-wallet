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
 * Pure-logic tests for the home "Join DashPay" tile gate
 * ([joinDashPayEligible]).
 *
 * Pins the defect these cover: on a mainnet wallet still scanning after a
 * restore the tile was offered mid-sync, because `isAbleToCreateIdentity`
 * (the `canJoin` input) carries no sync information — the sync factor is
 * commented out in `MainViewModel.combineLatestData()`. The tile must stay
 * hidden until the scan finishes and appear once it does, with `canJoin`
 * true throughout.
 */
class JoinDashPayEligibleTest {

    @Test
    fun `hidden while the wallet is still syncing`() {
        assertFalse(
            joinDashPayEligible(
                creationState = IdentityCreationState.NONE,
                canJoin = true,
                isSynced = false,
                hideJoinDashPayCard = false
            )
        )
    }

    @Test
    fun `shown once sync completes with the same canJoin verdict`() {
        assertTrue(
            joinDashPayEligible(
                creationState = IdentityCreationState.NONE,
                canJoin = true,
                isSynced = true,
                hideJoinDashPayCard = false
            )
        )
    }

    @Test
    fun `a synced wallet that cannot create an identity still shows nothing`() {
        assertFalse(
            joinDashPayEligible(
                creationState = IdentityCreationState.NONE,
                canJoin = false,
                isSynced = true,
                hideJoinDashPayCard = false
            )
        )
    }

    @Test
    fun `sync does not resurrect a dismissed tile`() {
        assertFalse(
            joinDashPayEligible(
                creationState = IdentityCreationState.NONE,
                canJoin = true,
                isSynced = true,
                hideJoinDashPayCard = true
            )
        )
    }

    @Test
    fun `a creation already under way keeps the tile hidden even when synced`() {
        // The identity-processing tile owns this state; the join nudge must not
        // compete with it.
        assertFalse(
            joinDashPayEligible(
                creationState = IdentityCreationState.IDENTITY_REGISTERING,
                canJoin = true,
                isSynced = true,
                hideJoinDashPayCard = false
            )
        )
    }

    @Test
    fun `no identity record yet keeps the tile hidden`() {
        // blockchainIdentityData is null until the row is first observed.
        assertFalse(
            joinDashPayEligible(
                creationState = null,
                canJoin = true,
                isSynced = true,
                hideJoinDashPayCard = false
            )
        )
    }
}
