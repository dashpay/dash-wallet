package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NotificationsLiveData(val walletApplication: WalletApplication, private val platformRepo: PlatformRepo) : LiveData<Resource<List<UsernameSearchResult>>>(), OnContactsUpdated {
    private var listening = false
    private var query = ""

    override fun onActive() {
        maybeAddEventListener()
        searchNotifications(query)
    }

    override fun onInactive() {
        maybeRemoveEventListener()
    }

    private fun maybeAddEventListener() {
        if (!listening && hasActiveObservers()) {
            PlatformRepo.addContactsUpdatedListener(this)
            listening = true
        }
    }

    private fun maybeRemoveEventListener() {
        if (listening) {
            PlatformRepo.removeContactsUpdatedListener(this)
            listening = false
        }
    }

    override fun onContactsUpdated() {
        searchNotifications(query)
    }

    fun searchNotifications(text: String = "") {
        query = text
        GlobalScope.launch {
            val contactRequests = platformRepo.searchContacts(query, UsernameSortOrderBy.DATE_ADDED)

            //TODO: gather other notification types
            // * invitations
            // * payments
            // * other

            postValue(contactRequests)
        }
    }
}