package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Resource

class ContactsUpdatedLiveData(walletApplication: WalletApplication, platformRepo: PlatformRepo)
    : ContactsBasedLiveData<Resource<Boolean>>(walletApplication, platformRepo) {

    override fun onContactsUpdated() {
        postValue(Resource.success(true))
    }
}