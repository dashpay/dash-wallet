/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui

import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.BlockchainStateDataProvider
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.core.PeerGroup.SyncStage
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InviteHandlerViewModel @Inject constructor(
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val topUpRepository: TopUpRepository
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    companion object {
        private val log = LoggerFactory.getLogger(InviteHandlerViewModel::class.java)
    }

    private val _inviteData = MutableLiveData<Resource<InvitationLinkData>>()
    val inviteData: LiveData<Resource<InvitationLinkData>>
        get() = _inviteData
    val invite: InvitationLinkData?
        get() = _inviteData.value?.data
    val isUsingInvite: Boolean
        get() = invite != null

    fun handleInvite(intent: Intent) {
        _inviteData.value = Resource.loading()
        FirebaseDynamicLinks.getInstance().getDynamicLink(intent).addOnSuccessListener {
            val link = it?.link
            if (link != null && InvitationLinkData.isValid(link)) {
                log.info("received invite $link")
                val invite = InvitationLinkData(link, false)
                handleInvite(invite)
            } else {
                log.info("invalid invite ignored")
            }
        }
    }

     fun handleInvite(invite: InvitationLinkData) {
        // TODO: remove this if block when Invite functionality is restored
        if (!Constants.SUPPORTS_INVITES) {
            log.info("invite ignored since they are currently disabled with Constants.SUPPORTS_INVITES = false")
            return
        }
        _inviteData.value = Resource.loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (blockchainStateProvider.getSyncStage() == SyncStage.BLOCKS) {
                    invite.isValid = topUpRepository.validateInvitation(invite)
                } else {
                    // assume the invite is valid if not synced
                    invite.isValid = true
                }

                if (hasIdentity) {
                    // we have an identity, so cancel this invite
                    _inviteData.postValue(Resource.canceled(invite))
                } else {
                    _inviteData.postValue(Resource.success(invite))
                }
            } catch (e: Exception) {
                log.info("error validating invite:", e)
                _inviteData.postValue(Resource.error(e, invite))
            }
        }
    }
}
