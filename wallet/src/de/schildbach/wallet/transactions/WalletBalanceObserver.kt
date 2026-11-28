/*
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.transactions

import com.google.common.base.Stopwatch
import de.schildbach.wallet.Constants
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.Wallet.BalanceType
import org.bitcoinj.wallet.listeners.WalletResetEventListener
import org.dash.wallet.common.data.WalletUIConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class WalletBalanceObserver(
    private val wallet: Wallet,
    private val walletUIConfig: WalletUIConfig
) {
    companion object {
        private val log = LoggerFactory.getLogger(WalletBalanceObserver::class.java)
    }
    private val emitterJob = SupervisorJob()
    private val emitterScope = CoroutineScope(Dispatchers.IO + emitterJob)

    private val _totalBalance = MutableStateFlow(Coin.ZERO)
    val totalBalance: StateFlow<Coin>
        get() = _totalBalance

    /**
     * False until [totalBalance] has been seeded with a real value — either the
     * persisted last balance ([emitLastBalances], an async DataStore read) or a
     * live wallet read ([emitBalances]). While false, [totalBalance] still holds
     * its `Coin.ZERO` construction seed, and synchronous readers (the widget's
     * `WalletApplication.getWalletBalance` redirect) should fall back to a direct
     * `wallet.getBalance(ESTIMATED)` read instead of rendering a false zero.
     */
    @Volatile
    var isSeeded: Boolean = false
        private set

    private val walletChangeListener = object : ThrottlingWalletChangeListener() {
        override fun onThrottledWalletChanged() {
            emitBalances()
        }
    }

    private val walletResetListener = object : WalletResetEventListener {
        override fun onWalletReset(wallet: Wallet?) {
            emitBalances()
        }
    }

    init {
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addResetEventListener(Threading.SAME_THREAD, walletResetListener)
        emitLastBalances()
    }

    fun close() {
        wallet.removeChangeEventListener(walletChangeListener)
        wallet.removeCoinsSentEventListener(walletChangeListener)
        wallet.removeCoinsReceivedEventListener(walletChangeListener)
        wallet.removeResetEventListener(walletResetListener)
        walletChangeListener.removeCallbacks()
        emitterJob.cancel()
    }

    private fun emitLastBalances() {
        emitterScope.launch {
            _totalBalance.value = Coin.valueOf(walletUIConfig.get(WalletUIConfig.LAST_TOTAL_BALANCE) ?: 0L)
            isSeeded = true
        }
    }

    fun emitBalances() {
        emitterScope.launch {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT)

            val totalBalance = wallet.getBalance(BalanceType.ESTIMATED)
            walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, totalBalance.value)
            _totalBalance.emit(totalBalance)
            isSeeded = true
        }
    }

    /** custom observer */
    fun observe(
        balanceType: BalanceType = BalanceType.ESTIMATED,
        coinSelector: CoinSelector? = null
    ): Flow<Coin> = callbackFlow {
        val emitterJob = SupervisorJob()
        val emitterScope = CoroutineScope(Dispatchers.IO + emitterJob)
        fun emitBalance() {
            emitterScope.launch {
                val watch = Stopwatch.createStarted()
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)

                trySend(
                    if (coinSelector != null) {
                        wallet.getBalance(coinSelector)
                    } else {
                        wallet.getBalance(balanceType)
                    }
                )
                log.info(
                    "process emit balance time: {} ms, selector {}",
                    watch.elapsed(TimeUnit.MILLISECONDS),
                    coinSelector?.javaClass?.simpleName
                )
            }
        }

        val walletChangeListener = object : ThrottlingWalletChangeListener() {
            override fun onThrottledWalletChanged() {
                emitBalance()
            }
        }

        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener)

        emitBalance()

        awaitClose {
            wallet.removeChangeEventListener(walletChangeListener)
            wallet.removeCoinsSentEventListener(walletChangeListener)
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            walletChangeListener.removeCallbacks()
            emitterJob.cancel()
        }
    }

}