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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.NotificationsForUserLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DashPayUserActivityViewModel(application: Application) : DashPayViewModel(application) {

    val userLiveData = MutableLiveData<UsernameSearchResult?>()
    var userData: UsernameSearchResult
        get() = userLiveData.value!!
        set(value) {
            userLiveData.value = value
        }

    fun sendContactRequest(refreshUserData: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val toUserId = userLiveData.value!!.dashPayProfile.userId
                val username = userLiveData.value!!.username
                val result = platformRepo.sendContactRequest(toUserId)
                if (refreshUserData) {
                    userLiveData.postValue(platformRepo.getUser(username).first())
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

    val notificationsForUser = NotificationsForUserLiveData(walletApplication, platformRepo, viewModelScope)
    fun initNotificationsForUser() {
        notificationsForUser.userId = userData.dashPayProfile.userId
    }
}