package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationsForUserLiveData(walletApplication: WalletApplication,
                                   platformRepo: PlatformRepo,
                                   scope: CoroutineScope) : NotificationsLiveData(walletApplication, platformRepo, scope) {

    override fun searchNotifications(userId: String) {
        this.query = userId
        scope.launch(Dispatchers.IO) {
            val results = arrayListOf<NotificationItem>()
            val contactRequests = platformRepo.searchContacts("", UsernameSortOrderBy.DATE_ADDED)

            if (contactRequests.data != null) {
                contactRequests.data.filter { cr ->
                    cr.dashPayProfile.userId == userId
                }.forEach {
                    results.add(NotificationItem(it))
                }
            }

            val blockchainIdentity = platformRepo.getBlockchainIdentity()!!

            val txs = blockchainIdentity.getContactTransactions(userId)

            txs.forEach {
                results.add(NotificationItem(it))
            }

            //TODO: gather other notification types
            // * invitations
            // * other

            results.sortByDescending {
                it.date
            }

            postValue(Resource.success(results))
        }
    }
}