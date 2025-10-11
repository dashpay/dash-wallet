/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.database.entity

import de.schildbach.wallet.data.InvitationLinkData
import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.dashpay.UsernameRequestStatus

open class BlockchainIdentityBaseData(
    var creationState: IdentityCreationState,
    var creationStateErrorMessage: String?,
    var username: String?,
    var usernameSecondary: String?,
    var userId: String?,
    var restoring: Boolean,
    var creditFundingTxId: Sha256Hash? = null,
    var usingInvite: Boolean = false,
    val invite: InvitationLinkData? = null,
    var requestedUsername: String? = null,
    var verificationLink: String? = null,
    val cancelledVerificationLink: Boolean? = null,
    var usernameRequested: UsernameRequestStatus? = null,
    var votingPeriodStart: Long? = null
) {

    val creationInProgress: Boolean
        get() = creationState > IdentityCreationState.NONE &&
                creationState < IdentityCreationState.VOTING &&
                creationStateErrorMessage == null && !restoring

    val votingInProgress: Boolean
        get() = creationState == IdentityCreationState.VOTING

    val creationComplete: Boolean
        get() = creationState >= IdentityCreationState.DONE

    val creationCompleteDismissed: Boolean
        get() = creationState == IdentityCreationState.DONE_AND_DISMISS

    val creationError: Boolean
        get() = creationStateErrorMessage != null
}