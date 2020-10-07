package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.canAffordIdentityCreation
import org.bitcoinj.wallet.Wallet

class CanAffordIdentityCreationLiveData(walletApplication: WalletApplication) : WalletBalanceBasedLiveData<Boolean>(walletApplication) {

    override fun onUpdate(wallet: Wallet) {
        value = wallet.canAffordIdentityCreation()
    }
}