package de.schildbach.wallet.ui.dashpay

import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import org.bitcoinj.wallet.Wallet

abstract class WalletBalanceBasedLiveData<T>(val walletApplication: WalletApplication = WalletApplication.getInstance())
    : LiveData<T>() {

    companion object {
        private const val THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
    }

    private var listening = false

    private val wallet: Wallet
        get() = walletApplication.wallet

    private val walletChangeListener = object : ThrottlingWalletChangeListener(THROTTLE_MS) {
        override fun onThrottledWalletChanged() {
            onUpdate(wallet)
        }
    }

    override fun onActive() {
        maybeAddEventListener()
        onUpdate(wallet)
    }

    override fun onInactive() {
        maybeRemoveEventListener()
    }

    private fun maybeAddEventListener() {
        if (!listening && hasActiveObservers()) {
            val mainThreadExecutor = ContextCompat.getMainExecutor(walletApplication)
            wallet.addCoinsReceivedEventListener(mainThreadExecutor, walletChangeListener)
            wallet.addCoinsSentEventListener(mainThreadExecutor, walletChangeListener)
            wallet.addChangeEventListener(mainThreadExecutor, walletChangeListener)
            wallet.addTransactionConfidenceEventListener(mainThreadExecutor, walletChangeListener)
            listening = true
        }
    }

    private fun maybeRemoveEventListener() {
        if (listening) {
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            wallet.removeCoinsSentEventListener(walletChangeListener)
            wallet.removeChangeEventListener(walletChangeListener)
            wallet.removeTransactionConfidenceEventListener(walletChangeListener)
            listening = false
        }
    }

    abstract fun onUpdate(wallet: Wallet)

}