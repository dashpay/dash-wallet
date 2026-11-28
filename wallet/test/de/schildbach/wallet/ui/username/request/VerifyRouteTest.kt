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
package de.schildbach.wallet.ui.username.request

import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Verify button of the request flow: a new request, or a replacement for a lost, locked or
 * taken name, is SUBMITTED; only verifying the request already in its vote just publishes the
 * link. A replacement after a lost vote left the record at VOTING/DONE, and the old "VOTING or
 * later means update the link" rule published a document and never registered the new name.
 */
class VerifyRouteTest {
    private val identityId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"

    private fun record(
        state: IdentityCreationState,
        username: String = "alice",
        requested: UsernameRequestStatus? = null,
        error: String? = null
    ) = BlockchainIdentityData(state, error, username, null, identityId, false, usernameRequested = requested)

    @Test
    fun `no identity yet, or one still being created, submits a new request`() {
        assertEquals(VerifyRoute.SUBMIT_NEW, verifyRoute(null, "alice"))
        assertEquals(VerifyRoute.SUBMIT_NEW, verifyRoute(record(IdentityCreationState.USERNAME_REGISTERING), "alice"))
    }

    @Test
    fun `a replacement after a lost or locked vote is submitted, not only verified`() {
        for (state in listOf(IdentityCreationState.VOTING, IdentityCreationState.DONE, IdentityCreationState.DONE_AND_DISMISS)) {
            assertEquals(
                VerifyRoute.SUBMIT_REPLACEMENT,
                verifyRoute(record(state, requested = UsernameRequestStatus.LOST_VOTE), "bobby")
            )
            assertEquals(
                VerifyRoute.SUBMIT_REPLACEMENT,
                verifyRoute(record(state, requested = UsernameRequestStatus.LOCKED), "bobby")
            )
        }
    }

    @Test
    fun `a replacement for a taken or failed name is submitted`() {
        val taken = "${SdkTransparentUsernameCreation.NAME_ONLY_RETRY_ERROR}: DuplicateUniqueIndexError"
        assertEquals(
            VerifyRoute.SUBMIT_REPLACEMENT,
            verifyRoute(record(IdentityCreationState.USERNAME_REGISTERING, error = taken), "bobby")
        )
        assertEquals(
            VerifyRoute.SUBMIT_REPLACEMENT,
            verifyRoute(record(IdentityCreationState.DONE, error = "rejected"), "bobby")
        )
    }

    @Test
    fun `a different name than the one in its vote is a replacement`() {
        assertEquals(VerifyRoute.SUBMIT_REPLACEMENT, verifyRoute(record(IdentityCreationState.VOTING), "bobby"))
    }

    @Test
    fun `verifying the request already in its vote only publishes the link`() {
        assertEquals(VerifyRoute.UPDATE_EXISTING_REQUEST, verifyRoute(record(IdentityCreationState.VOTING), "alice"))
        // the record holds the display label; the comparison is on DPNS-normalized forms
        assertEquals(VerifyRoute.UPDATE_EXISTING_REQUEST, verifyRoute(record(IdentityCreationState.VOTING, "Alice"), "alice"))
    }
}
