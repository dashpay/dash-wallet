package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NotificationCountLiveData(val walletApplication: WalletApplication, private val platformRepo: PlatformRepo) : LiveData<Int>(), OnContactsUpdated {
    private var listening = false

    override fun onActive() {
        maybeAddEventListener()
        getNotificationCount()
    }

    override fun onInactive() {
        maybeRemoveEventListener()
    }

    private fun maybeAddEventListener() {
        if (!listening && hasActiveObservers()) {
            platformRepo.addContactsUpdatedListener(this)
            listening = true
        }
    }

    private fun maybeRemoveEventListener() {
        if (listening) {
            platformRepo.removeContactsUpdatedListener(this)
            listening = false
        }
    }

    override fun onContactsUpdated() {
        getNotificationCount()
    }

    fun getNotificationCount() {
        GlobalScope.launch {
            val notificationCount = platformRepo.getNotificationCount(walletApplication.configuration.lastSeenNotificationTime)

            // TODO: Count other types of notifications such as:
            // * payments
            // * notifications

            if (notificationCount >= 0)
                postValue(notificationCount)
        }
    }

}