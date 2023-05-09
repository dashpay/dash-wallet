/*
 * Copyright 2020 Dash Core Group
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
package de.schildbach.wallet.ui.dashpay

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
open class DashPayViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    private val platformRepo: PlatformRepo,
    private val coinJoinService: CoinJoinService,
    private val blockchainState: BlockchainStateDao,
    private val dashPayProfile: DashPayProfileDaoAsync,
    private val userAlert: UserAlertDaoAsync,
    private val invitations: InvitationsDaoAsync,
    val platformSyncService: PlatformSyncService,
    var networkState: NetworkStateInt,
    private val platformBroadcastService: PlatformBroadcastService,
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashPayViewModel::class.java)
    }

    private val usernameLiveData = MutableLiveData<String?>()
    private val userSearchLiveData = MutableLiveData<UserSearch>()
    private val contactsLiveData = MutableLiveData<UsernameSearch>()
    private val contactUserIdLiveData = MutableLiveData<String?>()

    val notificationsLiveData = NotificationsLiveData(walletApplication, platformRepo, platformSyncService, viewModelScope)
    val contactsUpdatedLiveData = ContactsUpdatedLiveData(walletApplication, platformSyncService)
    val frequentContactsLiveData = FrequentContactsLiveData(walletApplication, platformRepo, platformSyncService, viewModelScope)
    val blockchainStateData = blockchainState.load()
    private val contactRequestLiveData = MutableLiveData<Pair<String, KeyParameter?>>()

    // Job instance (https://stackoverflow.com/questions/57723714/how-to-cancel-a-running-livedata-coroutine-block/57726583#57726583)
    private var getUsernameJob = Job()
    private var searchUsernamesJob = Job()
    private var searchContactsJob = Job()
    private var contactRequestJob = Job()
    private var getContactJob = Job()

    val recentlyModifiedContactsLiveData = MutableLiveData<HashSet<String>>()

    private var timerUsernameSearch: AnalyticsTimer? = null

    fun startUsernameSearchTimer() {
        timerUsernameSearch = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_SEARCH_UI)
    }

    fun reportUsernameSearchTime(resultSize: Int, searchTextSize: Int) {
        timerUsernameSearch?.logTiming(
            mapOf(
                "resultCount" to resultSize,
                "searchCount" to searchTextSize,
            ),
        )
    }

    fun isWifiConnected(): Boolean {
        return networkState.isWifiConnected()
    }
    fun setCoinJoinMode(mode: CoinJoinMode) {
        coinJoinService.setMode(mode)
    }
    val getUsernameLiveData = Transformations.switchMap(usernameLiveData) { username ->
        getUsernameJob.cancel()
        getUsernameJob = Job()
        liveData(context = getUsernameJob + Dispatchers.IO) {
            if (username != null) {
                emit(Resource.loading(null))
                emit(platformRepo.getUsername(username))
            } else {
                emit(Resource.canceled(null))
            }
        }
    }

    fun searchUsername(username: String?) {
        usernameLiveData.value = username
    }

    fun dashPayProfileData(username: String): LiveData<DashPayProfile?> {
        return dashPayProfile.loadByUsernameDistinct(username)
    }

    override fun onCleared() {
        super.onCleared()
        getUsernameJob.cancel()
        searchUsernamesJob.cancel()
    }

    //
    // Search Usernames that start with "text".  Results are a list of documents for names
    // starting with text.  If no results are found then an empty list is returned.
    //
    val searchUsernamesLiveData = Transformations.switchMap(userSearchLiveData) { search: UserSearch ->
        searchUsernamesJob.cancel()
        searchUsernamesJob = Job()
        liveData(context = searchUsernamesJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            try {
                val timerIsLock = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_SEARCH_QUERY)
                var result = platformRepo.searchUsernames(search.text, false, search.limit)
                result = result.filter { !search.excludeIds.contains(it.dashPayProfile.userId) }
                if (result.isNotEmpty()) {
                    val limit = result.size.coerceAtMost(search.limit)
                    result = result.subList(0, limit)
                }
                emit(Resource.success(result))
                if (search.text.length >= 3) {
                    timerIsLock.logTiming(
                        mapOf(
                            "resultCount" to result.size,
                            "searchCount" to search.text.length,
                        ),
                    )
                }
            } catch (ex: Exception) {
                analytics.logError(ex, "Failed to search user")
                emit(Resource.error(formatExceptionMessage("search usernames", ex), null))
            }
        }
    }

    fun searchUsernames(text: String, limit: Int = 100, removeContacts: Boolean = false) {
        val excludeIds = arrayListOf<String>()
        if (removeContacts) {
            searchContactsLiveData.value?.data?.forEach { excludeIds.add(it.dashPayProfile.userId) }
        }
        userSearchLiveData.value = UserSearch(text, limit, excludeIds)
    }

    //
    // Search (established contacts) Usernames and Display Names that contain "text".
    //
    val searchContactsLiveData = Transformations.switchMap(contactsLiveData) { usernameSearch: UsernameSearch ->
        searchContactsJob.cancel()
        searchContactsJob = Job()
        liveData(context = searchContactsJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            emit(platformRepo.searchContacts(usernameSearch.text, usernameSearch.orderBy))
        }
    }

    fun searchContacts(text: String, orderBy: UsernameSortOrderBy) {
        contactsLiveData.value = UsernameSearch(text, orderBy)
    }

    fun searchNotifications(text: String) {
        notificationsLiveData.query = text
    }

    fun usernameDoneAndDismiss() {
        viewModelScope.launch(Dispatchers.IO) {
            platformRepo.doneAndDismiss()
        }
    }

    fun updateDashPayState() {
        viewModelScope.launch(Dispatchers.IO) {
            platformSyncService.updateContactRequests()
        }
    }

    fun getNextContactAddress(userId: String, accountReference: Int): Address {
        return platformRepo.getNextContactAddress(userId, accountReference)
    }

    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)

    fun allUsersLiveData() = dashPayProfile.loadByUserId()

    fun sendContactRequest(toUserId: String) {
        var recentlyModifiedContacts = recentlyModifiedContactsLiveData.value
        if (recentlyModifiedContacts == null) {
            recentlyModifiedContacts = hashSetOf(toUserId)
        } else {
            recentlyModifiedContacts.add(toUserId)
        }
        recentlyModifiedContactsLiveData.postValue(recentlyModifiedContacts!!)
        SendContactRequestOperation(walletApplication)
            .create(toUserId)
            .enqueue()
    }

    val getContactRequestLiveData = Transformations.switchMap(contactRequestLiveData) {
        liveData(context = contactRequestJob + Dispatchers.IO) {
            if (it.second != null) {
                emit(Resource.loading(null))
                try {
                    val result = platformBroadcastService.sendContactRequest(it.first, it.second!!)
                    emit(Resource.success(result))
                } catch (ex: Exception) {
                    emit(Resource.error(formatExceptionMessage("send contact request", ex), null))
                }
            } else {
                emit(Resource.error("Failed to decrypt keys", null))
            }
        }
    }

    val getContactLiveData = Transformations.switchMap(contactUserIdLiveData) { userId ->
        getContactJob.cancel()
        getContactJob = Job()
        liveData(context = getContactJob + Dispatchers.IO) {
            if (userId != null) {
                emit(Resource.loading(null))
                emit(Resource.success(platformRepo.getLocalUserDataByUserId(userId)))
            } else {
                emit(Resource.canceled(null))
            }
        }
    }

    fun getContact(userId: String?) {
        contactUserIdLiveData.value = userId
    }

    fun getLocalContactDataByUserId(userId: String) = liveData(Dispatchers.IO) {
        try {
            emit(Resource.success(platformRepo.getLocalUserDataByUserId(userId)))
        } catch (ex: Exception) {
            emit(Resource.error(ex, null))
        }
    }

    fun getLocalContactDataByUsername(username: String) = liveData(Dispatchers.IO) {
        try {
            emit(Resource.success(platformRepo.getLocalUserDataByUsername(username)))
        } catch (ex: Exception) {
            emit(Resource.error(ex, null))
        }
    }

    fun getFrequentContacts() {
        frequentContactsLiveData.getFrequentContacts()
    }

    fun logEvent(event: String, params: Bundle = bundleOf()) {
        analytics.logEvent(event, params)
    }

    protected fun formatExceptionMessage(description: String, e: Exception): String {
        var msg = if (e.localizedMessage != null) {
            e.localizedMessage
        } else {
            e.message
        }
        if (msg == null) {
            msg = "Unknown error - ${e.javaClass.simpleName}"
        }
        log.error("$description: $msg", e)
        if (e is StatusRuntimeException) {
            log.error("---> ${e.trailers}")
        }
        e.printStackTrace()
        return msg
    }

    fun dismissUserAlert(alertId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            userAlert.dismiss(alertId)
            notificationsLiveData.onContactsUpdated()
        }
    }

    private inner class UserSearch(
        val text: String,
        val limit: Int = 100,
        val excludeIds: ArrayList<String> = arrayListOf(),
    )

    val inviteHistory = invitations.loadAll()
}
