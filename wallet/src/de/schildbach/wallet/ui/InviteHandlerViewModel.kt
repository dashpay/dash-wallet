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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.InvitationValidationState
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import android.net.Uri
import kotlinx.coroutines.withContext
import org.bitcoinj.core.PeerGroup.SyncStage
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InviteHandlerViewModel @Inject constructor(
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val topUpRepository: TopUpRepository,
    private val dashPayConfig: DashPayConfig
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    companion object {
        private val log = LoggerFactory.getLogger(InviteHandlerViewModel::class.java)
    }

    private val _invitation = MutableStateFlow<InvitationLinkData?>(null)
    val invitation: StateFlow<InvitationLinkData?> = _invitation.asStateFlow()

//    private val _validationState = MutableStateFlow(InvitationValidationState.NONE)
//    val validationState: StateFlow<InvitationValidationState?> = _validationState.asStateFlow()

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

    suspend fun handleInvite(intent: Intent): InvitationLinkData? = withContext(Dispatchers.IO) {
        val data = intent.data
        log.info("Processing intent data: $data")

        if (data != null) {
            // Check for AppsFlyer deep link parameter
            val deepLinkValue = data.getQueryParameter("af_dp")
            log.info("AppsFlyer deep_link_value: $deepLinkValue")

            if (deepLinkValue != null) {
                val link = Uri.parse(deepLinkValue)
                if (InvitationLinkData.isValid(link)) {
                    log.info("received valid invite from AppsFlyer: $link")
                    return@withContext InvitationLinkData(link)
                } else {
                    log.warn("Invalid invitation link from AppsFlyer: $link")
                }
            } else {
                // Check if the intent data itself is a valid invitation link
                if (InvitationLinkData.isValid(data)) {
                    log.info("received direct invite link: $data")
                    return@withContext InvitationLinkData(data)
                } else {
                    log.warn("Intent data is not a valid invitation link: $data")
                }
            }
        } else {
            log.warn("Intent data is null")
        }

        log.info("No valid invite found in intent")
        null
    }

    suspend fun setInvitationLink(invitation: InvitationLinkData, fromOnboarding: Boolean) {
        dashPayConfig.set(DashPayConfig.INVITATION_LINK, invitation.link.toString())
        dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, fromOnboarding)
    }

    suspend fun clearInvitation() {
        dashPayConfig.set(DashPayConfig.INVITATION_LINK, "")
        dashPayConfig.set(DashPayConfig.INVITATION_FROM_ONBOARDING, false)
    }

    suspend fun validateInvitation(): InvitationValidationState = withContext(Dispatchers.IO) {
        _invitation.value?.let { invite ->
            var updatedInvitation = invite.copy()
            if (hasIdentity || inVotingPeriod) {
                // we have an identity, don't check the validity
                updatedInvitation = updatedInvitation.validate(
                    if (inVotingPeriod) {
                        InvitationValidationState.ALREADY_HAS_REQUESTED_USERNAME
                    } else {
                        InvitationValidationState.ALREADY_HAS_IDENTITY
                    }
                )
            } else try {
                if (blockchainStateProvider.getSyncStage() == SyncStage.BLOCKS) {
                    updatedInvitation = updatedInvitation.copy(isValid = topUpRepository.validateInvitation(invite))
                }

                when {
                    updatedInvitation.isValid == null -> updatedInvitation = updatedInvitation.validate(InvitationValidationState.NOT_SYNCED)
                    updatedInvitation.isValid!! ->  updatedInvitation = updatedInvitation.validate(InvitationValidationState.VALID)
                    else -> {
                        log.info("${invite.isValid} invite already claimed?")
                         updatedInvitation = updatedInvitation.validate(InvitationValidationState.ALREADY_CLAIMED)
                    }
                }
            } catch (e: Exception) {
                log.info("error validating invite:", e)
                updatedInvitation = updatedInvitation.validate(InvitationValidationState.INVALID)
            }
            _invitation.value = updatedInvitation
            updatedInvitation.validationState
        } ?: InvitationValidationState.NONE
    }
}
