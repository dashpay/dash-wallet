package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ContactsUpdatedLiveData(val walletApplication: WalletApplication, private val platformRepo: PlatformRepo) : LiveData<Resource<Boolean>>(), OnContactsUpdated {
    private var listening = false
    private var query = ""

    override fun onActive() {
        maybeAddEventListener()
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
        postValue(Resource.success(true))
    }
}