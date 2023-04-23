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

data class BlockchainIdentityBaseData(val id: Int,
                                      val creationState: BlockchainIdentityData.CreationState,
                                      val creationStateErrorMessage: String?,
                                      val username: String?,
                                      val userId: String?,
                                      val restoring: Boolean,
                                      val creditFundingTxId: Sha256Hash? = null,
                                      val usingInvite: Boolean = false,
                                      val invite: InvitationLinkData? = null) {

    val creationInProgress: Boolean
        get() = creationState > BlockchainIdentityData.CreationState.NONE &&
                creationState < BlockchainIdentityData.CreationState.DONE &&
                creationStateErrorMessage == null

    val creationComplete: Boolean
        get() = creationState >= BlockchainIdentityData.CreationState.DONE

    val creationCompleteDismissed: Boolean
        get() = creationState == BlockchainIdentityData.CreationState.DONE_AND_DISMISS

    val creationError: Boolean
        get() = creationStateErrorMessage != null
}