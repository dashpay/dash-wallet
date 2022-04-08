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
package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.ui.dashpay.NotificationCountLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.WalletBalanceLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HeaderBalanceViewModel(application: Application) : AndroidViewModel(application) {

    /*companion object {
        private val log = LoggerFactory.getLogger(HeaderBalanceViewModel::class.java)
    }
    @Inject
    lateinit var analytics: AnalyticsService

     */
    val walletApplication = application as WalletApplication

    val platformRepo = PlatformRepo.getInstance()

    val walletBalanceData = WalletBalanceLiveData(walletApplication)
    val walletBalance
        get() = walletBalanceData.value

    val notificationCountData =
        NotificationCountLiveData(walletApplication, platformRepo, viewModelScope)
    val notificationCount: Int
        get() = notificationCountData.value ?: 0


    var blockchainState: BlockchainState? = null

    fun forceUpdateNotificationCount() {
        notificationCountData.onContactsUpdated()
        viewModelScope.launch(Dispatchers.IO) {
            platformRepo.updateContactRequests()
        }
    }

    /*var lastChangeToContacts: Long = 0

    val listener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            if (walletApplication.configuration.lastSeenNotificationTime > lastChangeToContacts) {
                startContactRequestTimer()
            }
        }

    }
    fun installConfigurationChangeListener() {
        lastChangeToContacts = walletApplication.configuration.lastSeenNotificationTime
        walletApplication.configuration.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeConfigureationChangeListener() {
        walletApplication.configuration.unregisterOnSharedPreferenceChangeListener(listener)
    }*/


}