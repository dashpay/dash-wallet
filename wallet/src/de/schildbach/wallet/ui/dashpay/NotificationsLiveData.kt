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

import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.PlatformSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class NotificationsLiveData(walletApplication: WalletApplication,
                                 val platformRepo: PlatformRepo,
                                 platformSyncService: PlatformSyncService,
                                 protected val scope: CoroutineScope)
    : ContactsBasedLiveData<Resource<List<NotificationItem>>>(walletApplication, platformSyncService) {

    var query = ""
        set(value) {
            field = value
            onContactsUpdated()
        }

    override fun onContactsUpdated() {
        scope.launch(Dispatchers.IO) {
            val results = arrayListOf<NotificationItem>()

            val userAlert = AppDatabase.getAppDatabase().userAlertDao().load(0L)
            if (userAlert != null && PlatformRepo.getInstance().shouldShowAlert()) {
                results.add(NotificationItemUserAlert(userAlert.stringResId, userAlert.iconResId))
            }

            val contactRequests = platformRepo.searchContacts(query, UsernameSortOrderBy.DATE_ADDED)

            //TODO: gather other notification types
            // * invitations
            // * payments are not included
            // * other

            contactRequests.data?.forEach {
                results.add(NotificationItemContact(it))
            }
            postValue(Resource.success(results))
        }
    }
}