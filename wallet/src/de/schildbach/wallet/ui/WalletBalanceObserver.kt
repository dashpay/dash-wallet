package de.schildbach.wallet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.Wallet.BalanceType

@ExperimentalCoroutinesApi
class WalletBalanceObserver(context: Context, private val wallet: Wallet) {
    private val broadcastManager = LocalBroadcastManager.getInstance(context.applicationContext)

    fun observe(): Flow<Coin> = callbackFlow {
        fun emitBalance() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
            trySend(wallet.getBalance(BalanceType.ESTIMATED))
        }

        val walletChangeListener = object : ThrottlingWalletChangeListener() {
            override fun onThrottledWalletChanged() {
                emitBalance()
            }
        }
        val walletChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                emitBalance()
            }
        }

        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)
        broadcastManager.registerReceiver(walletChangedReceiver,
            IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED)
        )

        emitBalance()

        awaitClose {
            Log.i("CROWDNODE", "Closing observer")
            broadcastManager.unregisterReceiver(walletChangedReceiver)
            wallet.removeChangeEventListener(walletChangeListener)
            wallet.removeCoinsSentEventListener(walletChangeListener)
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            walletChangeListener.removeCallbacks()
        }
    }
}