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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.Wallet.BalanceType
import org.slf4j.LoggerFactory

class WalletBalanceObserver(
    private val wallet: Wallet,
    private val balanceType: BalanceType = BalanceType.ESTIMATED,
    private val coinSelector: CoinSelector? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(WalletBalanceObserver::class.java)
    }
    private val emitterJob = SupervisorJob()
    private val emitterScope = CoroutineScope(Dispatchers.IO + emitterJob)
    fun observe(): Flow<Coin> = callbackFlow {
        fun emitBalance() {
            emitterScope.launch {
                //log.info("emitting balance {}", this@WalletBalanceObserver)
                //val watch = Stopwatch.createStarted()
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)

                trySend(
                    if (coinSelector != null) {
                        wallet.getBalance(coinSelector)
                    } else {
                        wallet.getBalance(balanceType)
                    }
                )
                //log.info("emit balance time: {} ms", watch.elapsed(TimeUnit.MILLISECONDS))
            }
        }

        val walletChangeListener = object : ThrottlingWalletChangeListener() {
            override fun onThrottledWalletChanged() {
                // log.info("emitting balance: wallet changed {}", this@WalletBalanceObserver)
                emitBalance()
            }
        }

        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener)
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)

        emitBalance()

        awaitClose {
            wallet.removeChangeEventListener(walletChangeListener)
            wallet.removeCoinsSentEventListener(walletChangeListener)
            wallet.removeCoinsReceivedEventListener(walletChangeListener)
            walletChangeListener.removeCallbacks()
        }
    }
}