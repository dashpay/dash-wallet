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
import de.schildbach.wallet.balanceOwnerId
import de.schildbach.wallet.service.platform.sdk.CutoverState
import de.schildbach.wallet.service.platform.sdk.dashjEngineMayStart
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import kotlinx.coroutines.CancellationException
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
    private val walletUIConfig: WalletUIConfig,
    private val dashPayConfig: DashPayConfig
) {
    companion object {
        private val log = LoggerFactory.getLogger(WalletBalanceObserver::class.java)
    }
    /** What this wallet's stored balance is tagged with; null (nothing read or stored) if unreadable. */
    private val ownerId: String? by lazy {
        runCatching { wallet.balanceOwnerId() }
            .onFailure { log.warn("cannot fingerprint the wallet; its last-known balance is not read or stored", it) }
            .getOrNull()
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
            // Only a value stored as this wallet's: an untagged one may be a previous wallet's.
            val stored = walletUIConfig.getLastTotalBalance(ownerId)
            if (stored != null) {
                _totalBalance.value = Coin.valueOf(stored)
            } else if (!isSeeded) {
                // None known (the first launch after the update): this wallet's own estimate,
                // not a zero, until a live figure arrives. Not stored: emitBalances decides that.
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                _totalBalance.value = wallet.getBalance(BalanceType.ESTIMATED)
            }
            isSeeded = true
        }
    }

    fun emitBalances() {
        emitterScope.launch {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT)

            val totalBalance = wallet.getBalance(BalanceType.ESTIMATED)
            // The stored balance is the L1 owner's. After the cutover the SDK writes it
            // (CutoverUiDataService); a dashj estimate from the Tools sync diagnostic must
            // not replace it.
            val owner = ownerId
            if (owner != null && dashjOwnsL1()) {
                walletUIConfig.setLastTotalBalance(totalBalance.value, owner)
            }
            _totalBalance.emit(totalBalance)
            isSeeded = true
        }
    }

    /** Whether dashj still owns L1 (no committed cutover). Fails closed: unreadable is no. */
    private suspend fun dashjOwnsL1(): Boolean = try {
        dashjEngineMayStart(
            CutoverState.fromStored(dashPayConfig.getPreservingErrors(DashPayConfig.CUTOVER_STATE))
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("cannot read the cutover state; not storing the dashj balance", e)
        false
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