package de.schildbach.wallet.util

import de.schildbach.wallet.Constants
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet

fun Wallet.canAffordIdentityCreation(): Boolean {
    val walletBalance: Coin = getBalance(Wallet.BalanceType.ESTIMATED)
    return (walletBalance.isGreaterThan(Constants.DASH_PAY_FEE)
            || walletBalance == Constants.DASH_PAY_FEE)
}
