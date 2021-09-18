/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.invite

import android.app.Application
import androidx.core.os.bundleOf
import androidx.lifecycle.MediatorLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.CanAffordIdentityCreationLiveData
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class CreateInviteViewModel @Inject constructor(
    application: Application,
    private val analytics: AnalyticsService
) : BaseProfileViewModel(application) {

    val blockchainStateData = AppDatabase.getAppDatabase().blockchainStateDao().load()
    val blockchainState: BlockchainState?
        get() = blockchainStateData.value

    val canAffordIdentityCreationLiveData = CanAffordIdentityCreationLiveData(walletApplication)

    val canAffordIdentityCreation: Boolean?
        get() = canAffordIdentityCreationLiveData.value

    val isAbleToCreateInviteLiveData = MediatorLiveData<Boolean>().apply {
        addSource(blockchainStateData) {
            value = combineLatestData()
        }
        addSource(blockchainIdentityData) {
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
        val noIdentityCreatedOrInProgress = (blockchainIdentityData.value == null) || blockchainIdentityData.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = canAffordIdentityCreationLiveData.value ?: false
        return isSynced && !noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    val invitationsLiveData = AppDatabase.getAppDatabase().invitationsDaoAsync().loadAll()

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
        analytics.logEvent(event, bundleOf())
    }

    private fun combineLatestInviteActionData(): Boolean {
        val hasInviteHistory = invitationCount > 0
        val canAffordInviteCreation = isAbleToCreateInvite
        return hasInviteHistory || canAffordInviteCreation
    }
}