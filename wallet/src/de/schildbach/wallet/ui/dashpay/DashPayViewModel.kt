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
import androidx.lifecycle.*
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearch
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bouncycastle.crypto.params.KeyParameter
import org.slf4j.LoggerFactory

open class DashPayViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        val log = LoggerFactory.getLogger(DashPayViewModel::class.java)
    }

    protected val platformRepo = PlatformRepo.getInstance()
    protected val walletApplication = application as WalletApplication

    private val usernameLiveData = MutableLiveData<String>()
    private val userSearchLiveData = MutableLiveData<String>()
    private val contactsLiveData = MutableLiveData<UsernameSearch>()
    private val contactUserIdLiveData = MutableLiveData<String>()

    val notificationsLiveData = NotificationsLiveData(walletApplication, platformRepo, viewModelScope)
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
    val searchUsernamesLiveData = Transformations.switchMap(userSearchLiveData) { text: String ->
        searchUsernamesJob.cancel()
        searchUsernamesJob = Job()
        liveData(context = searchUsernamesJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            try {
                val result = platformRepo.searchUsernames(text)
                emit(Resource.success(result))
            } catch (ex: Exception) {
                emit(Resource.error(formatExceptionMessage("search usernames", ex), null))
            }
        }
    }

    fun searchUsernames(text: String) {
        userSearchLiveData.value = text
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
            platformRepo.updateContactRequests()
        }
    }

    fun getNextContactAddress(userId: String): Address {
        return platformRepo.getNextContactAddress(userId)
    }

    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(application)

    fun allUsersLiveData() = AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserId()

    fun sendContactRequest(toUserId: String) {
        SendContactRequestOperation(walletApplication)
                .create(toUserId)
                .enqueue()
    }

    val getContactRequestLiveData = Transformations.switchMap(contactRequestLiveData) {
        liveData(context = contactRequestJob + Dispatchers.IO) {
            if (it.second != null) {
                emit(Resource.loading(null))
                try {
                    val result = platformRepo.sendContactRequest(it.first, it.second!!)
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

    protected fun formatExceptionMessage(description: String, e: Exception): String {
        var msg = if (e.localizedMessage != null) {
            e.localizedMessage
        } else {
            e.message
        }
        if (msg == null) {
            msg = "Unknown error - ${e.javaClass.simpleName}"
        }
        log.error("$description: $msg")
        if (e is StatusRuntimeException) {
            log.error("---> ${e.trailers}")
        }
        log.error(msg)
        e.printStackTrace()
        return msg
    }
}