/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.notification

import android.text.format.DateUtils
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.NotificationsLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    walletApplication: WalletApplication,
    platformRepo: PlatformRepo,
    dashPayProfileDao: DashPayProfileDao,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val userAlert: UserAlertDao,
    platformSyncService: PlatformSyncService,
    private val userAlertDao: UserAlertDao,
    private val dashPayConfig: DashPayConfig
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    val notificationsLiveData = NotificationsLiveData(walletApplication, platformRepo, platformSyncService, viewModelScope, userAlertDao)

    fun searchNotifications(text: String) {
        notificationsLiveData.query = text
    }
    fun dismissUserAlert(alertId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            userAlert.dismiss(alertId)
            notificationsLiveData.onContactsUpdated()
        }
    }

    suspend fun getLastNotificationTime(): Long =
        dashPayConfig.get(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) ?: 0

    fun setLastNotificationTime(time: Long) {
        viewModelScope.launch {
            val updatedTime = max(time, getLastNotificationTime()) + DateUtils.SECOND_IN_MILLIS
            dashPayConfig.set(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME, updatedTime)
        }
    }
}