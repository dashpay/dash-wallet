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
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.InvitationValidationState
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.PeerGroup.SyncStage
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InviteHandlerViewModel @Inject constructor(
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val topUpRepository: TopUpRepository,
    private val dashPayConfig: DashPayConfig,
    //private val configuration: Configuration
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    companion object {
        private val log = LoggerFactory.getLogger(InviteHandlerViewModel::class.java)
    }

    // TODO: remove Resource
//    private val _inviteData = MutableLiveData<Resource<InvitationLinkData>>()
//    val inviteData: LiveData<Resource<InvitationLinkData>>
//        get() = _inviteData

//    val invite: InvitationLinkData?
//        get() = _inviteData.value?.data

    private val _invitation = MutableStateFlow<InvitationLinkData?>(null)
    val invitation: StateFlow<InvitationLinkData?> = _invitation.asStateFlow()

    var fromOnboarding = false

    val isUsingInvite: Boolean
        get() = _invitation.value != null

    init {
        dashPayConfig.observe(DashPayConfig.INVITATION_LINK)
            .onEach {
                _invitation.value = if (!it.isNullOrEmpty()) {
                    InvitationLinkData(it.toUri())
                } else {
                    null
                }
            }
            .launchIn(viewModelScope)

        dashPayConfig.observe(DashPayConfig.INVITATION_FROM_ONBOARDING)
            .onEach {
                fromOnboarding = it ?: false
            }
            .launchIn(viewModelScope)
    }

    fun handleInvite(intent: Intent, lamda: (InvitationLinkData) -> Unit) {
        // _inviteData.value = Resource.loading()
        FirebaseDynamicLinks.getInstance().getDynamicLink(intent).addOnSuccessListener {
            val link = it?.link
            if (link != null && InvitationLinkData.isValid(link)) {
                log.info("received invite $link")
                val invite = InvitationLinkData(link)
                lamda.invoke(invite)
            } else {
                log.info("invalid invite ignored")
            }
        }
    }
//
//     fun handleInvite(invite: InvitationLinkData) {
//        // TODO: remove this if block when Invite functionality is restored
//        if (!Constants.SUPPORTS_INVITES) {
//            log.info("invite ignored since they are currently disabled with Constants.SUPPORTS_INVITES = false")
//            return
//        }
//        _inviteData.value = Resource.loading()
//        viewModelScope.launch(Dispatchers.IO) {
//            try {
//                if (blockchainStateProvider.getSyncStage() == SyncStage.BLOCKS) {
//                    invite.isValid = topUpRepository.validateInvitation(invite)
//                } else {
//                    // assume the invite is valid if not synced
//                    invite.isValid = true
//                }
//
//                if (hasIdentity) {
//                    // we have an identity, so cancel this invite
//                    _inviteData.postValue(Resource.canceled(invite))
//                } else {
//                    _inviteData.postValue(Resource.success(invite))
//                }
//            } catch (e: Exception) {
//                log.info("error validating invite:", e)
//                _inviteData.postValue(Resource.error(e, invite))
//            }
//        }
//    }

    suspend fun setInvitationLink(invitation: InvitationLinkData, fromOnboarding: Boolean) {
        // _inviteData.value = Resource.success(invitation)
        dashPayConfig.set(DashPayConfig.INVITATION_LINK, invitation.link.toString())
        dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, fromOnboarding)
    }

    suspend fun clearInvitation() {
        dashPayConfig.set(DashPayConfig.INVITATION_LINK, "")
        dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, false)
    }

    suspend fun validateInvitation(): InvitationValidationState = withContext(Dispatchers.IO) {
        _invitation.value?.let { invite ->
            //_inviteData.value = Resource.loading()
            try {
                if (blockchainStateProvider.getSyncStage() == SyncStage.BLOCKS) {
                    invite.isValid = topUpRepository.validateInvitation(invite)
                } else {
                    // assume the invite is valid if not synced
                    // invite.isValid = true
                }

                when {
                    hasIdentity -> {
                        // we have an identity, so cancel this invite
                        //_inviteData.postValue(Resource.canceled(invite))
                        invite.validationState = InvitationValidationState.ALREADY_HAS_IDENTITY
                    }
                    invite.isValid == null -> invite.validationState = InvitationValidationState.NOT_SYNCED
                    invite.isValid!! ->  invite.validationState = InvitationValidationState.VALID
                    else -> {
                        //_inviteData.postValue(Resource.success(invite))
                        log.info("${invite.isValid} invite already claimed?")
                        invite.validationState = InvitationValidationState.ALREADY_CLAIMED
                    }
                }
            } catch (e: Exception) {
                log.info("error validating invite:", e)
                //_inviteData.postValue(Resource.error(e, invite))
                invite.validationState = InvitationValidationState.INVALID
            }
            invite.validationState
        } ?: InvitationValidationState.NONE
    }
}
