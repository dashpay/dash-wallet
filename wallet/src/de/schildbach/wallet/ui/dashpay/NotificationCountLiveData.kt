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
package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationCountLiveData(
    walletApplication: WalletApplication,
    val platformRepo: PlatformRepo,
    platformSyncService: PlatformSyncService,
    private val dashPayConfig: DashPayConfig,
    private val scope: CoroutineScope
) : ContactsBasedLiveData<Int>(walletApplication, platformSyncService) {

    override fun onContactsUpdated() {
        scope.launch(Dispatchers.IO) {
            if (!dashPayConfig.areNotificationsDisabled()) {
                val lastSeen = dashPayConfig.get(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME) ?: 0
                val notificationCount = platformRepo.getNotificationCount(lastSeen)

                // TODO: Count other types of notifications such as:
                // * payments
                // * notifications

                // TODO: ???
                if (notificationCount >= 0) {
                    postValue(notificationCount)
                }
            } else {
                postValue(0)
            }
        }
    }
}
