package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bitcoinj.evolution.EvolutionContact

class NotificationsForUserLiveData(walletApplication: WalletApplication, platformRepo: PlatformRepo) : NotificationsLiveData(walletApplication, platformRepo) {

    override fun searchNotifications(userId: String) {
        this.query = userId
        GlobalScope.launch {
            val results = arrayListOf<NotificationItem>()
            val contactRequests = platformRepo.searchContacts("", UsernameSortOrderBy.DATE_ADDED)

            //TODO: gather other notification types
            // * invitations
            // * payments
            // * other

            if(contactRequests.data != null) {
                contactRequests.data.filter {
                    cr -> cr.dashPayProfile.userId == userId
                }.forEach {
                    results.add(NotificationItem(it))
                }
            }

            val blockchainIdentity = platformRepo.getBlockchainIdentity()!!

            val contact = EvolutionContact(blockchainIdentity.uniqueIdString, userId)

            val txs = walletApplication.wallet.getTransactionsWithFriend(contact)

            txs.forEach {
                results.add(NotificationItem(it))
            }

            postValue(Resource.success(results))
        }
    }
}