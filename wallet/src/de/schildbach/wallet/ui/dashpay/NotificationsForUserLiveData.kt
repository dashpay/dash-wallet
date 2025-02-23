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

package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.PlatformSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dashj.platform.dpp.identifier.Identifier

class NotificationsForUserLiveData(walletApplication: WalletApplication,
                                   platformSyncService: PlatformSyncService,
                                   val platformRepo: PlatformRepo,
                                   private val scope: CoroutineScope)
    : ContactsBasedLiveData<Resource<List<NotificationItem>>>(walletApplication, platformSyncService) {

    var userId: String? = null
        set(value) {
            field = value
            onContactsUpdated()
        }

    override fun onContactsUpdated() {
        if (userId.isNullOrEmpty()) {
            return
        }
        scope.launch(Dispatchers.IO) {
            val results = arrayListOf<NotificationItem>()
            val contactRequests = platformRepo.searchContacts("", UsernameSortOrderBy.DATE_ADDED, true)
            var accountReference: Int = 0
            if (contactRequests.data != null) {
                contactRequests.data.filter { cr ->
                    cr.dashPayProfile.userId == userId
                }.forEach {
                    if (it.type == UsernameSearchResult.Type.REQUEST_RECEIVED) {
                        results.add(NotificationItemContact(it, true))
                        accountReference = it.fromContactRequest!!.accountReference
                    } else {
                        results.add(NotificationItemContact(it))
                    }
                    if (it.type == UsernameSearchResult.Type.CONTACT_ESTABLISHED) {
                        val incoming = (it.toContactRequest!!.timestamp > it.fromContactRequest!!.timestamp)
                        val invitationItem =
                                if (incoming) it.copy(toContactRequest = null) else it.copy(fromContactRequest = null)
                        results.add(NotificationItemContact(invitationItem, isInvitationOfEstablished = true))
                        accountReference = it.fromContactRequest!!.accountReference
                    }
                }
            }

            val blockchainIdentity = platformRepo.blockchainIdentity
            val txs = blockchainIdentity.getContactTransactions(Identifier.from(userId), accountReference)

            txs.forEach {
                results.add(NotificationItemPayment(it))
            }

            //TODO: gather other notification types
            // * invitations
            // * other

            val sortedResults = results.sortedWith(
                compareByDescending { item: NotificationItem -> item.getDate() }.thenBy { item: NotificationItem -> item.getId() }
            )

            postValue(Resource.success(sortedResults))
        }
    }
}