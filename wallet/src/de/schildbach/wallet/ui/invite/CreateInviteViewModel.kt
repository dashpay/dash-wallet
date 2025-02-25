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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class CreateInviteViewModel @Inject constructor(
    private val walletData: WalletDataProvider,
    private val analytics: AnalyticsService,
     blockchainStateDao: BlockchainStateDao,
    invitationsDao: InvitationsDao,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    val blockchainStateData = blockchainStateDao.observeState().asLiveData(viewModelScope.coroutineContext)
    //val blockchainState: LiveData<BlockchainState>
    //    get() = blockchainStateData.asLiveData(viewModelScope)

    val isAbleToCreateInviteLiveData = MediatorLiveData<Boolean>().apply {
        addSource(blockchainStateData) {
            value = combineLatestData()
        }
        addSource(blockchainIdentity) {
            value = combineLatestData()
        }
        addSource(walletData.observeTotalBalance().asLiveData()) {
            value = combineLatestData()
        }
    }
    val isAbleToCreateInvite: Boolean
        get() = isAbleToCreateInviteLiveData.value ?: false

    private fun combineLatestData(): Boolean {
        val isSynced = blockchainStateData.value?.isSynced() ?: false
        val identityCreated = blockchainIdentity.value?.creationComplete ?: false
        return isSynced && identityCreated
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

    private fun canAffordIdentityCreation(): Boolean =
        !walletData.getWalletBalance().isLessThan(Constants.DASH_PAY_FEE)
}
