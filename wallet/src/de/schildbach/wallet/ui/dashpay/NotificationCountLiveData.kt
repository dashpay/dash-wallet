package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationCountLiveData(walletApplication: WalletApplication, platformRepo: PlatformRepo,
                                private val scope: CoroutineScope) : ContactsBasedLiveData<Int>(walletApplication, platformRepo) {

    override fun onContactsUpdated() {
        scope.launch(Dispatchers.IO) {
            val notificationCount = platformRepo.getNotificationCount(walletApplication.configuration.lastSeenNotificationTime)

            // TODO: Count other types of notifications such as:
            // * payments
            // * notifications

            if (notificationCount >= 0)
                postValue(notificationCount)
        }
    }
}