/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.service

import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.coinjoin.CoinJoinClientManager
import org.bitcoinj.coinjoin.CoinJoinClientOptions
import org.bitcoinj.coinjoin.PoolMessage
import org.bitcoinj.coinjoin.PoolStatus
import org.bitcoinj.coinjoin.callbacks.RequestDecryptedKey
import org.bitcoinj.coinjoin.callbacks.RequestKeyParameter
import org.bitcoinj.coinjoin.listeners.MixingCompleteListener
import org.bitcoinj.coinjoin.listeners.SessionCompleteListener
import org.bitcoinj.coinjoin.progress.MixingProgressTracker
import org.bitcoinj.coinjoin.utils.CoinJoinManager
import org.bitcoinj.coinjoin.utils.ProTxToOutpoint
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

enum class CoinJoinMode {
    BASIC,
    INTERMEDIATE,
    ADVANCED,
}

interface CoinJoinService {
    fun needsToMix(amount: Coin): Boolean
    fun getMode(): CoinJoinMode
    fun setMode(mode: CoinJoinMode)
    fun configureMixing(
        amount: Coin,
        requestKeyParameter: RequestKeyParameter,
        requestDecryptedKey: RequestDecryptedKey,
    )

    suspend fun prepareAndStartMixing()
    fun getMixingStatus(): MixingStatus
    suspend fun waitForMixing()
    suspend fun waitForMixingWithException()
}

enum class MixingStatus {
    NOT_STARTED,
    MIXING,
    PAUSED,
    FINISHED,
    ERROR,
}

@Singleton
class CoinJoinMixingService @Inject constructor(
    val walletDataProvider: WalletDataProvider,
    val walletApplication: WalletApplication,
    val blockchainStateProvider: BlockchainStateProvider,
) : CoinJoinService {

    companion object {
        val log: Logger = LoggerFactory.getLogger(CoinJoinMixingService::class.java)
    }

    private val coinJoinManager: CoinJoinManager?
        get() = walletDataProvider.wallet?.context?.coinJoinManager
    private lateinit var clientManager: CoinJoinClientManager

    private var mixingCompleteListeners: ArrayList<MixingCompleteListener> = arrayListOf()
    private var sessionCompleteListeners: ArrayList<SessionCompleteListener> = arrayListOf()

    private var mixingStatus: MixingStatus = MixingStatus.NOT_STARTED
    private var mode: CoinJoinMode = CoinJoinMode.BASIC

    private val coroutineScope = CoroutineScope(
        Executors.newFixedThreadPool(2).asCoroutineDispatcher(),
    )

    private var blockChain: AbstractBlockChain? = null
    private val isBlockChainSet: Boolean
        get() = blockChain != null
    private var networkStatus: NetworkStatus = NetworkStatus.UNKNOWN

    // https://stackoverflow.com/questions/55421710/how-to-suspend-kotlin-coroutine-until-notified
    private val mixingMutex = Mutex(locked = true)
    private var exception: Throwable? = null
    override suspend fun waitForMixing() = mixingMutex.withLock {}
    override suspend fun waitForMixingWithException() {
        waitForMixing()
        exception?.let {
            throw it
        }
    }
    private fun setMixingComplete() {
        mixingMutex.unlock()
    }

    init {
        blockchainStateProvider.observeNetworkStatus()
            .filterNot { it == NetworkStatus.UNKNOWN }
            .onEach { status ->
                updateNetworkStatus(status)
            }
            .launchIn(coroutineScope)

        blockchainStateProvider.observeBlockChain()
            .filterNotNull()
            .onEach { blockChain ->
                updateBlockChain(blockChain)
            }
            .launchIn(coroutineScope)
    }

    private suspend fun updateState(mixingStatus: MixingStatus, networkStatus: NetworkStatus, blockChain: AbstractBlockChain?) {
        setBlockchain(blockChain)

        when {
            networkStatus == NetworkStatus.UNKNOWN -> return
            mixingStatus == MixingStatus.MIXING && networkStatus == NetworkStatus.CONNECTED && isBlockChainSet -> {
                // start mixing
                prepareMixing()
                startMixing()
            }
            this.mixingStatus == MixingStatus.MIXING && mixingStatus == MixingStatus.FINISHED -> {
                // finish mixing
                stopMixing()
                setMixingComplete()
            }
            mixingStatus == MixingStatus.MIXING && this.networkStatus == NetworkStatus.CONNECTED && networkStatus == NetworkStatus.NOT_AVAILABLE -> {
                // pause mixing
                stopMixing()
            }
            mixingStatus == MixingStatus.PAUSED && this.networkStatus == NetworkStatus.CONNECTING && networkStatus == NetworkStatus.CONNECTED && isBlockChainSet -> {
                // resume mixing
                prepareMixing()
                startMixing()
            }
            mixingStatus == MixingStatus.ERROR -> setMixingComplete()
        }
        log.info("coinjoin-updateState: $mixingStatus, $networkStatus, ${blockChain != null}")
        this.networkStatus = networkStatus
        this.mixingStatus = mixingStatus
    }

    private suspend fun updateBlockChain(blockChain: AbstractBlockChain) {
        updateState(mixingStatus, networkStatus, blockChain)
    }

    private suspend fun updateNetworkStatus(networkStatus: NetworkStatus) {
        updateState(mixingStatus, networkStatus, blockChain)
    }

    private suspend fun updateMixingStatus(mixingStatus: MixingStatus) {
        updateState(mixingStatus, networkStatus, blockChain)
    }

    private var mixingProgressTracker: MixingProgressTracker = object : MixingProgressTracker() {
        override fun onMixingComplete(wallet: WalletEx?, statusList: MutableList<PoolStatus>?) {
            super.onMixingComplete(wallet, statusList)
            log.info("Mixing Complete.  {}% mixed", progress)
            // TODO: _progressFlow.emit(progress)
        }

        override fun onSessionStarted(
            wallet: WalletEx?,
            sessionId: Int,
            denomination: Int,
            message: PoolMessage?,
        ) {
            super.onSessionStarted(wallet, sessionId, denomination, message)
            log.info("Session {} started.  {}% mixed", sessionId, progress)
        }

        override fun onSessionComplete(
            wallet: WalletEx?,
            sessionId: Int,
            denomination: Int,
            message: PoolMessage?,
        ) {
            super.onSessionComplete(wallet, sessionId, denomination, message)
            // TODO: _progressFlow.emit(progress)
            log.info("Session {} complete. {}% mixed -- {}", sessionId, progress, message)
        }
    }

    init {
        // remove with Core 19
        ProTxToOutpoint.initialize(Constants.NETWORK_PARAMETERS)
    }

    override fun getMixingStatus(): MixingStatus {
        return mixingStatus
    }

    override fun needsToMix(amount: Coin): Boolean {
        return walletApplication.wallet?.getBalance(Wallet.BalanceType.COINJOIN_SPENDABLE)
            ?.isLessThan(amount) ?: false
    }

    override fun getMode(): CoinJoinMode {
        return mode
    }

    override fun setMode(mode: CoinJoinMode) {
        this.mode = mode
    }

    override fun configureMixing(
        amount: Coin,
        requestKeyParameter: RequestKeyParameter,
        requestDecryptedKey: RequestDecryptedKey,
    ) {
        CoinJoinClientOptions.setSessions(4)
        CoinJoinClientOptions.setAmount(amount)
        CoinJoinClientOptions.setMultiSessionEnabled(false)

        when (mode) {
            CoinJoinMode.BASIC -> CoinJoinClientOptions.setAmount(Coin.ZERO)
            CoinJoinMode.INTERMEDIATE -> CoinJoinClientOptions.setRounds(1)
            CoinJoinMode.ADVANCED -> CoinJoinClientOptions.setRounds(2)
        }

        // TODO: have CoinJoinClientOptions.toString instead do this
        log.info("mixing configuration:  { rounds: ${CoinJoinClientOptions.getRounds()}, sessions: ${CoinJoinClientOptions.getSessions()}, amount: ${amount.toFriendlyString()}}")
        coinJoinManager?.run {
            setRequestKeyParameter(requestKeyParameter)
            setRequestDecryptedKey(requestDecryptedKey)
        }
    }

    override suspend fun prepareAndStartMixing() {
        // do we need to mix?
        val wallet = walletDataProvider.wallet!! as WalletEx
        // the mixed balance must meet the getAmount() requirement and all denominated coins must be mixed
        if (wallet.coinJoinBalance.isGreaterThanOrEqualTo(CoinJoinClientOptions.getAmount()) &&
            wallet.coinJoinBalance.equals(wallet.denominatedBalance)
        ) {
            setMixingComplete()
        } else {
            updateMixingStatus(MixingStatus.MIXING)
        }
    }

    private suspend fun prepareMixing() {
        CoinJoinClientOptions.setEnabled(true)
        val wallet = walletDataProvider.wallet!!
        addMixingCompleteListener(mixingProgressTracker)
        addSessionCompleteListener(mixingProgressTracker)
        coinJoinManager?.run {
            clientManager = CoinJoinClientManager(wallet)
            coinJoinClientManagers[wallet.description] = clientManager
            // this allows mixing to wait for the last transaction to be confirmed
            clientManager.addContinueMixingOnError(PoolStatus.ERR_NOT_ENOUGH_FUNDS)
            // wait until the masternode sync system fixes itself
            clientManager.addContinueMixingOnError(PoolStatus.ERR_NO_MASTERNODES_DETECTED)
            clientManager.setStopOnNothingToDo(true)
            val mixingFinished = clientManager.mixingFinishedFuture

            val mixingCompleteListener =
                MixingCompleteListener { _, statusList ->
                    statusList?.let {
                        for (status in it) {
                            if (status != PoolStatus.FINISHED) {
                                coroutineScope.launch(Dispatchers.IO) { updateMixingStatus(MixingStatus.ERROR) }
                                exception = Exception("Mixing stopped before completion ${status.name}")
                            }
                        }
                    }
                }

            mixingFinished.addListener({
                log.info("Mixing complete.")
                removeMixingCompleteListener(mixingCompleteListener)
                if (mixingFinished.get()) {
                    coroutineScope.launch(Dispatchers.IO) { updateMixingStatus(MixingStatus.FINISHED) }
                } else {
                    coroutineScope.launch(Dispatchers.IO) { updateMixingStatus(MixingStatus.PAUSED) }
                }
            }, Threading.SAME_THREAD)

            addMixingCompleteListener(Threading.SAME_THREAD, mixingCompleteListener)
        }
    }

    private suspend fun startMixing(): Boolean {
        Context.propagate(walletDataProvider.wallet!!.context)
        clientManager.setBlockChain(blockChain)
        return if (!clientManager.startMixing()) {
            log.info("Mixing has been started already.")
            false
        } else {
            // run this on a different thread?
            val asyncStart = coroutineScope.async(Dispatchers.IO) {
                Context.propagate(walletDataProvider.wallet!!.context)
                clientManager.doAutomaticDenominating()
            }
            val result = asyncStart.await()
            log.info("Mixing " + if (result) "started successfully" else "start failed: " + clientManager.statuses + ", will retry")
            true
        }
    }

    private fun stopMixing() {
        log.info("Mixing process will stop...")
        if (coinJoinManager == null || !this::clientManager.isInitialized) {
            return
        }

        // if mixing is not complete, then tell the future we didn't finish yet
        if (!clientManager.mixingFinishedFuture.isDone) {
            clientManager.mixingFinishedFuture.set(false)
        }
        // remove all listeners
        mixingCompleteListeners.forEach { coinJoinManager?.removeMixingCompleteListener(it) }
        sessionCompleteListeners.forEach { coinJoinManager?.removeSessionCompleteListener(it) }
        coinJoinManager?.stop()
        CoinJoinClientOptions.setEnabled(false)
    }

    private fun setBlockchain(blockChain: AbstractBlockChain?) {
        if (blockChain != this.blockChain) {
            log.info("setting blockchain in clientManager to ${blockChain?.chainHead?.height ?: "null"}")
            this.blockChain = blockChain
        }

        this.blockChain = blockChain
    }

    fun addSessionCompleteListener(sessionCompleteListener: SessionCompleteListener) {
        sessionCompleteListeners.add(sessionCompleteListener)
        coinJoinManager?.addSessionCompleteListener(Threading.SAME_THREAD, sessionCompleteListener)
    }

    fun addMixingCompleteListener(mixingCompleteListener: MixingCompleteListener) {
        mixingCompleteListeners.add(mixingCompleteListener)
        coinJoinManager?.addMixingCompleteListener(Threading.SAME_THREAD, mixingCompleteListener)
    }

    fun removeMixingCompleteListener(mixingCompleteListener: MixingCompleteListener) {
        coinJoinManager?.removeMixingCompleteListener(mixingCompleteListener)
    }

    // TODO: private val _progressFlow = MutableStateFlow(0.00)
    // TODO: override suspend fun getMixingProgress(): Flow<Double + other data> = _progressFlow
    // TODO: suspend fun setProgress(progress: Double) = _progressFlow.emit(progress)
}
