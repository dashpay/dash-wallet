package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication

abstract class ContactsBasedLiveData<T>(val walletApplication: WalletApplication,
                                        val platformRepo: PlatformRepo) : LiveData<T>(), OnContactsUpdated {

    private var listening = false

    override fun onActive() {
        maybeAddEventListener()
        onContactsUpdated()
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

    abstract override fun onContactsUpdated()
}