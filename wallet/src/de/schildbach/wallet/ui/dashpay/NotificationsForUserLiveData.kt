package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NotificationsForUserLiveData(walletApplication: WalletApplication, platformRepo: PlatformRepo) : NotificationsLiveData(walletApplication, platformRepo) {

    override fun searchNotifications(userId: String) {
        this.query = userId
        GlobalScope.launch {
            val results = arrayListOf<NotificationItem>()
            val contactRequests = platformRepo.searchContacts("", UsernameSortOrderBy.DATE_ADDED, true)

            if (contactRequests.data != null) {
                contactRequests.data.filter { cr ->
                    cr.dashPayProfile.userId == userId
                }.forEach {
                    results.add(NotificationItemContact(it))
                    if (it.type == UsernameSearchResult.Type.CONTACT_ESTABLISHED) {
                        val invitationItem = if (it.incoming) it.copy(toContactRequest = null) else it.copy(fromContactRequest = null)
                        results.add(NotificationItemContact(invitationItem, isInvitationOfEstablished = true))
                    }
                }
            }

            val blockchainIdentity = platformRepo.getBlockchainIdentity()!!

            val txs = blockchainIdentity.getContactTransactions(userId)

            txs.forEach {
                results.add(NotificationItemPayment(it))
            }

            //TODO: gather other notification types
            // * invitations
            // * other

            results.sortByDescending {
                it.getDate()
            }

            postValue(Resource.success(results))
        }
    }
}