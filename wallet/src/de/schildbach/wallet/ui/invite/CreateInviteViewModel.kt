/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.ui.invite

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.BlockchainIdentityDataDao
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.CanAffordIdentityCreationLiveData
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class CreateInviteViewModel @Inject constructor(
    walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    blockchainStateDao: BlockchainStateDao,
    invitationsDao: InvitationsDao,
    blockchainIdentityDataDao: BlockchainIdentityDataDao,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    val blockchainStateData = blockchainStateDao.load()
    val blockchainState: BlockchainState?
        get() = blockchainStateData.value

    val canAffordIdentityCreationLiveData = CanAffordIdentityCreationLiveData(walletApplication)

    val canAffordIdentityCreation: Boolean?
        get() = canAffordIdentityCreationLiveData.value

    val isAbleToCreateInviteLiveData = MediatorLiveData<Boolean>().apply {
        addSource(blockchainStateData) {
            value = combineLatestData()
        }
        addSource(blockchainIdentity) {
            value = combineLatestData()
        }
        addSource(canAffordIdentityCreationLiveData) {
            value = combineLatestData()
        }
    }
    val isAbleToCreateInvite: Boolean
        get() = isAbleToCreateInviteLiveData.value ?: false

    private fun combineLatestData(): Boolean {
        val isSynced = blockchainStateData.value?.isSynced() ?: false
        val noIdentityCreatedOrInProgress = (blockchainIdentity.value == null) || blockchainIdentity.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = canAffordIdentityCreationLiveData.value ?: false
        return isSynced && !noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    val invitationsLiveData = invitationsDao.observe().asLiveData()

    val invitationCount: Int
        get() = invitationsLiveData.value?.size ?: 0

    val isAbleToPerformInviteAction = MediatorLiveData<Boolean>().apply {
        addSource(isAbleToCreateInviteLiveData) {
            value = combineLatestInviteActionData()
        }
        addSource(invitationsLiveData) {
            value = combineLatestInviteActionData()
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    private fun combineLatestInviteActionData(): Boolean {
        val hasInviteHistory = invitationCount > 0
        val canAffordInviteCreation = isAbleToCreateInvite
        return hasInviteHistory || canAffordInviteCreation
    }
}
