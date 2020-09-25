/*
 * Copyright 2020 Dash Core Group.
 *
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

package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.dashpay.NotificationsForUserLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class DashPayUserActivityViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        val log = LoggerFactory.getLogger(DashPayUserActivityViewModel::class.java)
    }

    private val platformRepo = PlatformRepo.getInstance()
    private val walletApplication = application as WalletApplication

    val userLiveData = MutableLiveData<UsernameSearchResult?>()
    var userData: UsernameSearchResult
        get() = userLiveData.value!!
        set(value) {
            userLiveData.value = value
        }

    val sendContactRequestState by lazy {
        SendContactRequestOperation.operationStatus(application, userData.dashPayProfile.userId)
    }

    fun sendContactRequest(refreshUserData: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val toUserId = userLiveData.value!!.dashPayProfile.userId
                val username = userLiveData.value!!.username
                val result = platformRepo.sendContactRequest(toUserId)
                if (refreshUserData) {
                    userLiveData.postValue(platformRepo.getUser(userData.username).first())
                } else {
                    userLiveData.value!!.toContactRequest = result
                    userLiveData.postValue(userLiveData.value)  //notify observers
                }
            } catch (ex: Exception) {
                log.error(ex.message, ex)
                userLiveData.postValue(userLiveData.value)  //notify observers
            }
        }
    }

    fun refreshUserData() {
        viewModelScope.launch(Dispatchers.IO) {
            userLiveData.postValue(platformRepo.getUser(userData.username).first())
        }
    }

    fun sendContactRequest() {
        SendContactRequestOperation(walletApplication)
                .create(userData.dashPayProfile.userId)
                .enqueue()
    }

    val notificationsForUser = NotificationsForUserLiveData(walletApplication, platformRepo, viewModelScope)
    fun initNotificationsForUser() {
        notificationsForUser.userId = userData.dashPayProfile.userId
    }
}