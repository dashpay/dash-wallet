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
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.*
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearch
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DeriveKeyTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import java.lang.Exception

class DashPayViewModel(application: Application) : AndroidViewModel(application) {

    private val platformRepo = PlatformRepo(application as WalletApplication)

    private val usernameLiveData = MutableLiveData<String>()
    private val userSearchLiveData = MutableLiveData<String>()
    private val contactsLiveData = MutableLiveData<UsernameSearch>()
    private val notificationCountLiveData = MutableLiveData<Long>()
    private val contactRequestLiveData = MutableLiveData<Pair<String, KeyParameter?>>()

    // Job instance (https://stackoverflow.com/questions/57723714/how-to-cancel-a-running-livedata-coroutine-block/57726583#57726583)
    private var getUsernameJob = Job()
    private var searchUsernamesJob = Job()
    private var searchContactsJob = Job()
    private var contactRequestJob = Job()

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

    fun getNotificationCount(date: Long) {
        notificationCountLiveData.value = date
    }

    val getNotificationCountLiveData = Transformations.switchMap(notificationCountLiveData) { date: Long ->
        liveData(Dispatchers.IO) {
            val count = platformRepo.getNotificationCount(date)
            if (count >= 0)
                emit(count)
        }
    }

    fun usernameDoneAndDismiss() {
        viewModelScope.launch {
            platformRepo.doneAndDismiss()
        }
    }

    //TODO: this can probably be simplified using coroutines
    private fun deriveEncryptionKey(onSuccess: (KeyParameter) -> Unit, onError: (Exception) -> Unit) {
        val walletApplication = WalletApplication.getInstance()
        val backgroundThread = HandlerThread("background", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        val backgroundHandler = android.os.Handler(backgroundThread.looper)
        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        object : DeriveKeyTask(backgroundHandler, walletApplication.scryptIterationsTarget()) {
            override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
                onSuccess(encryptionKey)
            }

            override fun onFailure(ex: KeyCrypterException) {
                onError(ex)
            }
        }.deriveKey(walletApplication.wallet, password)
    }

    fun sendContactRequest(toUserId: String) {
        deriveEncryptionKey({ encryptionKey: KeyParameter ->
            contactRequestLiveData.value = Pair(toUserId, encryptionKey)
        }, {
            contactRequestLiveData.value = Pair(toUserId, null)
        })
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

}