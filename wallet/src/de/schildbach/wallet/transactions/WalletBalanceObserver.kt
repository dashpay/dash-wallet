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
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.Wallet.BalanceType
import org.slf4j.LoggerFactory


class WalletBalanceObserver(
    private val wallet: Wallet
) {
    companion object {
        private val log = LoggerFactory.getLogger(WalletBalanceObserver::class.java)
    }
    private val emitterJob = SupervisorJob()
    private val emitterScope = CoroutineScope(Dispatchers.IO + emitterJob)

    private val _totalBalance = MutableStateFlow(Coin.ZERO)
    val totalBalance: StateFlow<Coin>
        get() = _totalBalance

    private val _mixedBalance = MutableStateFlow(Coin.ZERO)
    val mixedBalance: StateFlow<Coin>
        get() = _mixedBalance

    private val _spendableBalance = MutableStateFlow(Coin.ZERO)
    val spendableBalance: StateFlow<Coin>
        get() = _spendableBalance

    private val walletChangeListener = object : ThrottlingWalletChangeListener() {
        override fun onThrottledWalletChanged() {
            // log.info("emitting balance: wallet changed {}", this@WalletBalanceObserver)
            emitBalances()
        }
    }

    init {
        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)
        emitBalances()
    }

    fun close() {
        wallet.removeChangeEventListener(walletChangeListener)
        walletChangeListener.removeCallbacks()
        emitterJob.cancel()
    }

    fun emitBalances() {
        emitterScope.launch {
            //log.info("emitting balance {}", this@WalletBalanceObserver)
            //val watch = Stopwatch.createStarted()
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT)

            _mixedBalance.emit(wallet.getBalance(BalanceType.COINJOIN_SPENDABLE))
            _totalBalance.emit(wallet.getBalance(BalanceType.ESTIMATED))
            //log.info("emit balance time: {} ms", watch.elapsed(TimeUnit.MILLISECONDS))
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

        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)

        emitBalance()

        awaitClose {
            wallet.removeChangeEventListener(walletChangeListener)
            walletChangeListener.removeCallbacks()
            emitterJob.cancel()
        }
    }

    /** the emitted balance depends on the current [CoinJoinMode] and if mixing is ongoing */
    fun observeSpendable(coinJoinService: CoinJoinService): Flow<Coin> = callbackFlow {
        val emitterJob = SupervisorJob()
        val emitterScope = CoroutineScope(Dispatchers.IO + emitterJob)

        suspend fun emitSpendingBalance(isMixing: Boolean) {
            _spendableBalance.emit(
                if (isMixing) {
                    _mixedBalance.value
                } else {
                    _totalBalance.value
                }
            )
        }

        fun emitBalance() {
            emitterScope.launch {
                //org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                emitSpendingBalance(coinJoinService.isMixing())
            }
        }

        coinJoinService.observeMixing()
            .onEach { isMixing ->
                emitSpendingBalance(isMixing)
                val sourceFlow = if (isMixing) _mixedBalance else _totalBalance
                sourceFlow.onEach {
                    _spendableBalance.value = it
                }.launchIn(emitterScope)
            }
            .launchIn(emitterScope)


//        val walletChangeListener = object : ThrottlingWalletChangeListener() {
//            override fun onThrottledWalletChanged() {
//                // log.info("emitting balance: wallet changed {}", this@WalletBalanceObserver)
//                emitBalance()
//            }
//        }

//        wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener)

        emitBalance()

        awaitClose {
            //wallet.removeChangeEventListener(walletChangeListener)
            //walletChangeListener.removeCallbacks()
            emitterJob.cancel()
        }
    }
}