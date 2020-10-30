package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.WalletApplication
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.Wallet.BalanceType

class WalletBalanceLiveData(walletApplication: WalletApplication = WalletApplication.getInstance())
    : WalletBalanceBasedLiveData<Coin>(walletApplication) {

    override fun onUpdate(wallet: Wallet) {
        value = wallet.getBalance(BalanceType.ESTIMATED)
    }
}