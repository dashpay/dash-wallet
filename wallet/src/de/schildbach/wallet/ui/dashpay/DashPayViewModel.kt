/*
 * Copyright (c) 2020. Dash Core Group.
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
package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.UsernameSearch
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet.ui.username.CreateUsernameArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.slf4j.LoggerFactory
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
open class DashPayViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    private val platformRepo: PlatformRepo,
    blockchainState: BlockchainStateDao,
    dashPayProfileDao: DashPayProfileDao,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val invitations: InvitationsDao,
    val platformSyncService: PlatformSyncService,
    private val platformBroadcastService: PlatformBroadcastService,
    contactRequestDao: DashPayContactRequestDao,
    private val dashPayConfig: DashPayConfig
) : BaseContactsViewModel(blockchainIdentityDataDao, dashPayProfileDao, contactRequestDao) {

    companion object {
        private val log = LoggerFactory.getLogger(DashPayViewModel::class.java)
    }

    private val usernameLiveData = MutableLiveData<String?>()
    private val userSearchLiveData = MutableLiveData<UserSearch>()
    private val contactsLiveData = MutableLiveData<UsernameSearch>()
    private val contactUserIdLiveData = MutableLiveData<String?>()

    val contactsUpdatedLiveData = ContactsUpdatedLiveData(platformSyncService)
    val _frequentContacts = MutableStateFlow<List<UsernameSearchResult>>(listOf())
    val frequentContacts = _frequentContacts.asStateFlow()
    val blockchainStateData = blockchainState.observeState()

    private val contactRequestLiveData = MutableLiveData<Pair<String, KeyParameter?>>()

    // Job instance (https://stackoverflow.com/questions/57723714/how-to-cancel-a-running-livedata-coroutine-block/57726583#57726583)
    private var getUsernameJob = Job()
    private var searchUsernamesJob = Job()
    private var searchContactsJob = Job()
    private var contactRequestJob = Job()
    private var getContactJob = Job()

    var createUsernameArgs :CreateUsernameArgs? = null

    val recentlyModifiedContactsLiveData = MutableLiveData<HashSet<String>>()

    private var timerUsernameSearch: AnalyticsTimer? = null

    init {
        dashPayConfig.observe(DashPayConfig.FREQUENT_CONTACTS)
            .filterNotNull()
            .onEach { frequentContacts ->
                _frequentContacts.value = frequentContacts.mapNotNull { contactIdentifier ->
                    val profile = platformRepo.loadProfileByUserId(contactIdentifier)
                    platformRepo.loadContactRequestsAndReturn(profile)
                }
            }
            .launchIn(viewModelScope)
    }

    suspend fun isDashPayInfoShown(): Boolean =
        dashPayConfig.get(DashPayConfig.HAS_DASH_PAY_INFO_SCREEN_BEEN_SHOWN) ?: false

    suspend fun setIsDashPayInfoShown(isShown: Boolean) {
        dashPayConfig.set(DashPayConfig.HAS_DASH_PAY_INFO_SCREEN_BEEN_SHOWN, isShown)
    }
    fun startUsernameSearchTimer() {
        timerUsernameSearch = AnalyticsTimer(analytics, log, AnalyticsConstants.Process.PROCESS_USERNAME_SEARCH_UI)
    }

    fun reportUsernameSearchTime(resultSize: Int, searchTextSize: Int) {
        timerUsernameSearch?.logTiming(
            mapOf(
                AnalyticsConstants.Parameter.ARG1 to resultSize,
                AnalyticsConstants.Parameter.ARG2 to searchTextSize
            )
        )
    }

    val getUsernameLiveData = usernameLiveData.switchMap { username ->
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

    override fun onCleared() {
        super.onCleared()
        getUsernameJob.cancel()
        searchUsernamesJob.cancel()
    }

    //
    // Search Usernames that start with "text".  Results are a list of documents for names
    // starting with text.  If no results are found then an empty list is returned.
    //
    val searchUsernamesLiveData = userSearchLiveData.switchMap { search: UserSearch ->
        searchUsernamesJob.cancel()
        searchUsernamesJob = Job()
        liveData(context = searchUsernamesJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            try {
                val timerIsLock = AnalyticsTimer(
                    analytics,
                    log,
                    AnalyticsConstants.Process.PROCESS_USERNAME_SEARCH_QUERY
                )
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
                            AnalyticsConstants.Parameter.ARG1 to result.size,
                            AnalyticsConstants.Parameter.ARG2 to search.text.length
                        )
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
    val searchContactsLiveData = contactsLiveData.switchMap { usernameSearch: UsernameSearch ->
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

    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)

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

    val getContactRequestLiveData = contactRequestLiveData.switchMap {
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

    val getContactLiveData = contactUserIdLiveData.switchMap { userId ->
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

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    private fun formatExceptionMessage(description: String, e: Exception): String {
        var msg = if (e.localizedMessage != null) {
            e.localizedMessage
        } else {
            e.message
        }
        if (msg == null) {
            msg = "Unknown error - ${e.javaClass.simpleName}"
        }
        log.error("$description: $msg", e)
        e.printStackTrace()
        return msg
    }

    suspend fun getInviteHistory() = withContext(Dispatchers.IO) { invitations.loadAll() }
    suspend fun getInviteCount() = withContext(Dispatchers.IO) { invitations.count() }

    fun contactRequestsTo(userId: String): LiveData<List<DashPayContactRequest>> =
        contactRequestDao.observeToOthers(userId).distinctUntilChanged().asLiveData()

    private inner class UserSearch(
        val text: String,
        val limit: Int = 100,
        val excludeIds: ArrayList<String> = arrayListOf()
    )

    suspend fun hasEnoughCredits(): CreditBalanceInfo {
        return platformRepo.getIdentityBalance()
    }
}
