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

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import androidx.work.*
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearch
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import kotlinx.coroutines.*
import org.bitcoinj.core.Address
import org.bouncycastle.crypto.params.KeyParameter

class DashPayViewModel(application: Application) : AndroidViewModel(application) {

    private val platformRepo = PlatformRepo.getInstance()
    private val walletApplication = application as WalletApplication

    private val mWorkManager: WorkManager = WorkManager.getInstance(application)

    val sendContactRequestWorkInfo: LiveData<List<WorkInfo>>
        get() = mWorkManager.getWorkInfosByTagLiveData(SendContactRequestOperation.TAG)

    private val usernameLiveData = MutableLiveData<String>()
    private val userSearchLiveData = MutableLiveData<String>()
    private val contactsLiveData = MutableLiveData<UsernameSearch>()
    private val contactUserIdLiveData = MutableLiveData<String>()
    private val getUserLiveData = MutableLiveData<String>()

    val notificationCountLiveData = NotificationCountLiveData(walletApplication, platformRepo, viewModelScope)
    val notificationsLiveData = NotificationsLiveData(walletApplication, platformRepo, viewModelScope)
    val notificationsForUserLiveData = NotificationsForUserLiveData(walletApplication, platformRepo, viewModelScope)
    val contactsUpdatedLiveData = ContactsUpdatedLiveData(walletApplication, platformRepo)
    val frequentContactsLiveData = FrequentContactsLiveData(walletApplication, platformRepo, viewModelScope)
    private val contactRequestLiveData = MutableLiveData<Pair<String, KeyParameter?>>()

    // Job instance (https://stackoverflow.com/questions/57723714/how-to-cancel-a-running-livedata-coroutine-block/57726583#57726583)
    private var getUsernameJob = Job()
    private var searchUsernamesJob = Job()
    private var searchContactsJob = Job()
    private var contactRequestJob = Job()
    private var getContactJob = Job()

    val getUsernameLiveData = Transformations.switchMap(usernameLiveData) { username ->
        getUsernameJob.cancel()
        getUsernameJob = Job()
        liveData(context = getUsernameJob + Dispatchers.IO) {
            if (username != null) {
                emit(Resource.loading(null))
                emit(platformRepo.getUsername(username))
            } else {
                emit(Resource.canceled())
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
    val searchUsernamesLiveData = Transformations.switchMap(userSearchLiveData) { text: String ->
        searchUsernamesJob.cancel()
        searchUsernamesJob = Job()
        liveData(context = searchUsernamesJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            emit(platformRepo.searchUsernames(text))
        }
    }

    fun searchUsernames(text: String) {
        userSearchLiveData.value = text
    }

    fun loadUser(username: String) {
        getUserLiveData.value = username
        viewModelScope.launch(Dispatchers.IO) {
            val result = platformRepo.searchUsernames(username, true)
            if (result.exception == null && result.data != null && result.data.isNotEmpty()) {
                platformRepo.lastLoadedUser = result.data.first()
            }
        }
    }

    //
    // Search Usernames and Display Names that contain "text".
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

    val isPlatformAvailableLiveData = liveData(Dispatchers.IO) {
        emit(Resource.loading(null))
        emit(platformRepo.isPlatformAvailable())
    }

    fun searchNotifications(text: String) {
        notificationsLiveData.searchNotifications(text)
    }

    fun searchNotificationsForUser(userId: String) {
        notificationsForUserLiveData.searchNotifications(userId)
    }

    fun getNotificationCount() {
        notificationCountLiveData.getNotificationCount()
    }

    fun usernameDoneAndDismiss() {
        viewModelScope.launch(Dispatchers.IO) {
            platformRepo.doneAndDismiss()
        }
    }

    fun updateDashPayState() {
        viewModelScope.launch(Dispatchers.IO) {
            platformRepo.updateContactRequests()
        }
    }

    fun getNextContactAddress(userId: String): Address {
        return platformRepo.getNextContactAddress(userId)
    }

    fun sendContactRequestWork(toUserId: String) {
        println("doWork#0")
        SendContactRequestOperation()
                .create(walletApplication, toUserId)
                .enqueue()
    }

    val getContactRequestLiveData = Transformations.switchMap(contactRequestLiveData) { it ->
        liveData(context = contactRequestJob + Dispatchers.IO) {
            if (it.second != null) {
                emit(Resource.loading(null))
                emit(platformRepo.sendContactRequest(it.first, it.second!!))
            } else {
                emit(Resource.error("Failed to decrypt keys"))
            }
        }
    }

    val getContactLiveData = Transformations.switchMap(contactUserIdLiveData) { userId ->
        getContactJob.cancel()
        getContactJob = Job()
        liveData(context = getContactJob + Dispatchers.IO) {
            if (userId != null) {
                emit(Resource.loading(null))
                emit(Resource.success(platformRepo.getLocalUsernameSearchResult(userId)))
            } else {
                emit(Resource.canceled())
            }
        }
    }

    fun getContact(userId: String?) {
        contactUserIdLiveData.value = userId
    }

    fun getFrequentContacts() {
        frequentContactsLiveData.getFrequentContacts()
    }

    // is there a better place for this
    fun forgetAutoAcceptContactRequest(userId: String) {
        val sharedPreferences = walletApplication.getSharedPreferences("forgetPendingRequestUserId", Context.MODE_PRIVATE)
        sharedPreferences.edit()
                .putBoolean(userId, false)
                .apply()
    }

    fun shouldAutoAcceptContactRequest(userId: String): Boolean {
        val sharedPreferences = walletApplication.getSharedPreferences("forgetPendingRequestUserId", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean(userId, true)
    }

    internal fun cancelSendContactRequest() {
        mWorkManager.cancelUniqueWork(SendContactRequestOperation.WORK_NAME)
    }
}