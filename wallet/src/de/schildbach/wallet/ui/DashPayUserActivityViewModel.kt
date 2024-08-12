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
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.NotificationsForUserLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.TransactionsLiveData
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.lang.Exception
import javax.inject.Inject

@HiltViewModel
class DashPayUserActivityViewModel @Inject constructor(
    application: Application,
    val platformSyncService: PlatformSyncService,
    private val analytics: AnalyticsService,
    val platformRepo: PlatformRepo
) : AndroidViewModel(application) {

    companion object {
        val log = LoggerFactory.getLogger(DashPayUserActivityViewModel::class.java)
    }

    //private val platformRepo = PlatformRepo.getInstance()
    private val walletApplication = application as WalletApplication
    val transactionsLiveData = TransactionsLiveData()

    lateinit var userData: UsernameSearchResult

    val userLiveData by lazy {
        liveData(Dispatchers.IO) {
            try {
                userData = platformRepo.getUser(userData.username).first()
                platformRepo.platform.stateRepository.addValidIdentity(userData.dashPayProfile.userIdentifier)
                sendContactRequestState
                emit(Resource.success(userData))
            } catch (ex: Exception) {
                emit(Resource.error("Failed to load Profile", null))
            }
        }
    }

    val sendContactRequestState by lazy {
        SendContactRequestOperation.operationStatus(
            application, userData.dashPayProfile.userId, analytics
        )
    }

    fun sendContactRequest() {
        SendContactRequestOperation(walletApplication)
                .create(userData.dashPayProfile.userId)
                .enqueue()
    }

    val notificationsForUser = NotificationsForUserLiveData(walletApplication, platformSyncService, platformRepo, viewModelScope)
    fun initNotificationsForUser() {
        notificationsForUser.userId = userData.dashPayProfile.userId
    }

    fun initUserData(username: String): LiveData<UsernameSearchResult> = liveData(Dispatchers.IO) {
        platformRepo.getLocalUserDataByUsername(username)?.let {
            log.info("obtained local user data for $username")
            userData = it
            emit(it)
        }
    }

    fun updateProfileData(dashPayProfile: DashPayProfile) {
        viewModelScope.launch {
            platformRepo.addOrUpdateDashPayProfile(dashPayProfile)
        }
    }

    suspend fun hasEnoughCredits(): CreditBalanceInfo {
        return platformRepo.getIdentityBalance()
    }
}