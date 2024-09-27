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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class InviteHandlerViewModel @Inject constructor(
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    private val platformRepo: PlatformRepo
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    private val log = LoggerFactory.getLogger(InviteHandlerViewModel::class.java)

    val inviteData = MutableLiveData<Resource<InvitationLinkData>>()

    val invite: InvitationLinkData
        get() = inviteData.value!!.data!!

    fun handleInvite(intent: Intent) {
        FirebaseDynamicLinks.getInstance().getDynamicLink(intent).addOnSuccessListener {
            val link = it?.link
            if (link != null && InvitationLinkData.isValid(link)) {
                log.debug("received invite $link")
                val invite = InvitationLinkData(link, false)
                handleInvite(invite)
            } else {
                log.debug("invalid invite ignored")
            }
        }
    }

    fun handleInvite(invite: InvitationLinkData) {
        inviteData.value = Resource.loading()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                invite.validation = platformRepo.validateInvitation(invite)

                if (hasIdentity) {
                    // we have an identity, so cancel this invite
                    inviteData.postValue(Resource.canceled(invite))
                } else {
                    inviteData.postValue(Resource.success(invite))
                }
            } catch (e: Exception) {
                inviteData.postValue(Resource.error(e, invite))
            }
        }
    }
}
