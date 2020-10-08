package de.schildbach.wallet.ui.dashpay

import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet

class WalletBalanceLiveData : WalletBalanceBasedLiveData<Coin>() {
    override fun onUpdate(wallet: Wallet) {
        this.value = wallet.balance
    }
}