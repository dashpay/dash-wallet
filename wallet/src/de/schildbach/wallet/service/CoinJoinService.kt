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

import com.google.common.base.Stopwatch
import com.google.common.collect.Comparators.max
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.bitcoinj.coinjoin.CoinJoin
import org.bitcoinj.coinjoin.CoinJoinClientManager
import org.bitcoinj.coinjoin.CoinJoinClientOptions
import org.bitcoinj.coinjoin.Denomination
import org.bitcoinj.coinjoin.PoolMessage
import org.bitcoinj.coinjoin.PoolState
import org.bitcoinj.coinjoin.PoolStatus
import org.bitcoinj.coinjoin.callbacks.RequestDecryptedKey
import org.bitcoinj.coinjoin.callbacks.RequestKeyParameter
import org.bitcoinj.coinjoin.listeners.MixingCompleteListener
import org.bitcoinj.coinjoin.listeners.SessionCompleteListener
import org.bitcoinj.coinjoin.progress.MixingProgressTracker
import org.bitcoinj.coinjoin.utils.CoinJoinManager
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.MasternodeAddress
import org.bitcoinj.utils.ContextPropagatingThreadFactory
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class CoinJoinMode {
    NONE,
    INTERMEDIATE,
    ADVANCED
}

interface CoinJoinService {
    //var mode: CoinJoinMode
    val mixingStatus: MixingStatus
    val mixingProgress: Flow<Double>

    //fun needsToMix(amount: Coin): Boolean
    suspend fun configureMixing(
        amount: Coin,
        requestKeyParameter: RequestKeyParameter,
        requestDecryptedKey: RequestDecryptedKey,
        restoreFromConfig: Boolean
    )

    suspend fun configureMixing(restoreFromConfig: Boolean)

    //suspend fun prepareAndStartMixing()
    // suspend fun waitForMixing()
    // suspend fun waitForMixingWithException()
}

enum class MixingStatus {
    NOT_STARTED,
    MIXING,
    PAUSED,
    FINISHED,
    ERROR
}

@Singleton
class CoinJoinMixingService @Inject constructor(
    val walletDataProvider: WalletDataProvider,
    blockchainStateProvider: BlockchainStateProvider,
    private val config: CoinJoinConfig,
    private val platformRepo: PlatformRepo
) : CoinJoinService {

    companion object {
        val log: Logger = LoggerFactory.getLogger(CoinJoinMixingService::class.java)
        const val DEFAULT_MULTISESSION = false
        const val DEFAULT_ROUNDS = 1
        const val DEFAULT_SESSIONS = 4

        // these are not for production
        val FAST_MIXING_DASHPAY_FEE: Coin = Coin.valueOf(1000, 0)
        val FAST_MIXING_DENOMINATIONS_REMOVE = listOf<Denomination>() // Denomination.THOUSANDTH)
    }

    private val coinJoinManager: CoinJoinManager?
        get() = walletDataProvider.wallet?.context?.coinJoinManager
    private lateinit var clientManager: CoinJoinClientManager

    private var mixingCompleteListeners: ArrayList<MixingCompleteListener> = arrayListOf()
    private var sessionCompleteListeners: ArrayList<SessionCompleteListener> = arrayListOf()

    var mode: CoinJoinMode = CoinJoinMode.NONE
    override var mixingStatus: MixingStatus = MixingStatus.NOT_STARTED
        private set

    private val coroutineScope = CoroutineScope(
        Executors.newFixedThreadPool(2, ContextPropagatingThreadFactory("coinjoin-pool")).asCoroutineDispatcher()
    )

    private val uiCoroutineScope = CoroutineScope(Dispatchers.Main)

    private var blockChain: AbstractBlockChain? = null
    private val isBlockChainSet: Boolean
        get() = blockChain != null
    private var networkStatus: NetworkStatus = NetworkStatus.UNKNOWN
    private var hasAnonymizableBalance: Boolean = false

    // https://stackoverflow.com/questions/55421710/how-to-suspend-kotlin-coroutine-until-notified
//    private val mixingMutex = Mutex(locked = true)
    private val updateMutex = Mutex(locked = false)
    private val updateMixingStateMutex = Mutex(locked = false)
    private var exception: Throwable? = null

    override val mixingProgress: Flow<Double>
        get() = _progressFlow
    private val _progressFlow = MutableStateFlow(0.00)

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

        blockchainStateProvider.observeState()
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { blockChainState ->
                log.info("coinjoin: new block: ${blockChainState.bestChainHeight}")
                updateBalance(walletDataProvider.getWalletBalance())
            }
            .launchIn(coroutineScope)

        walletDataProvider.observeBalance()
            .onEach { balance ->
                log.info("coinjoin: total balance: $balance (before distinct)")
            }
            //.distinctUntilChanged()
            .onEach { balance ->
                // switch to our context
                coroutineScope.launch {
                    updateBalance(balance)
                }
            }
            .launchIn(uiCoroutineScope) // required for observeBalance

        config.observeMode()
            .filterNotNull()
            .onEach {
                updateMode(it)
            }
            .launchIn(coroutineScope)
    }

    private suspend fun updateBalance(balance: Coin) {
        // leave this ui scope
        Context.propagate(walletDataProvider.wallet!!.context)
        CoinJoinClientOptions.setAmount(balance)
        log.info("coinjoin: total balance: ${balance.toFriendlyString()}")
        val walletEx = walletDataProvider.wallet as WalletEx
        log.info("coinjoin: mixed balance: ${walletEx.coinJoinBalance.toFriendlyString()}")
        val anonBalance = walletEx.getAnonymizableBalance(false, false)
        log.info("coinjoin: anonymizable balance {}", anonBalance.toFriendlyString())

        val hasAnonymizableBalance = anonBalance.isGreaterThan(CoinJoin.getSmallestDenomination())
        log.info("coinjoin: mixing can occur: $hasAnonymizableBalance")
        updateState(mode, hasAnonymizableBalance, networkStatus, blockChain)
    }

    private suspend fun updateState(
        mode: CoinJoinMode,
        hasAnonymizableBalance: Boolean,
        networkStatus: NetworkStatus,
        blockChain: AbstractBlockChain?
    ) {
        updateMutex.lock()
        log.info("coinjoin-updateState: ${this.mode}, ${this.hasAnonymizableBalance}, ${this.networkStatus}, ${blockChain != null}")
        try {
            setBlockchain(blockChain)
            log.info("coinjoin-updateState: $mode, $hasAnonymizableBalance, $networkStatus, ${blockChain != null}")
            val previousNetworkStatus = this.networkStatus
            this.networkStatus = networkStatus
            this.mixingStatus = mixingStatus
            this.hasAnonymizableBalance = hasAnonymizableBalance
            this.mode = mode

            if (mode == CoinJoinMode.NONE) {
                updateMixingState(MixingStatus.NOT_STARTED)
            } else {
                if (hasAnonymizableBalance) {
                    if (networkStatus == NetworkStatus.CONNECTED && isBlockChainSet) {
                        updateMixingState(MixingStatus.MIXING)
                    } else {
                        updateMixingState(MixingStatus.PAUSED)
                    }
                } else {
                    updateMixingState(MixingStatus.FINISHED)
                }
            }
        } finally {
            updateMutex.unlock()
            log.info("updateMutex is unlocked")
        }
    }

    private suspend fun updateMixingState(
        mixingStatus: MixingStatus
    ) {
        updateMixingStateMutex.lock()
        try {

            val previousMixingStatus = this.mixingStatus
            this.mixingStatus = mixingStatus
            log.info("coinjoin-updateMixingState: $previousMixingStatus -> $mixingStatus")

            when {
                mixingStatus == MixingStatus.MIXING && previousMixingStatus != MixingStatus.MIXING -> {
                    // start mixing
                    prepareMixing()
                    startMixing()
                }

                previousMixingStatus == MixingStatus.MIXING && mixingStatus != MixingStatus.MIXING -> {
                    // finish mixing
                    stopMixing()
                    // setMixingComplete()
                }
            }
        } finally {
            updateMixingStateMutex.unlock()
        }
    }

    private suspend fun updateBlockChain(blockChain: AbstractBlockChain) {
        updateState(mode, hasAnonymizableBalance, networkStatus, blockChain)
    }

    private suspend fun updateNetworkStatus(networkStatus: NetworkStatus) {
        updateState(mode, hasAnonymizableBalance, networkStatus, blockChain)
    }

    private suspend fun updateMode(mode: CoinJoinMode) {
        CoinJoinClientOptions.setEnabled(mode != CoinJoinMode.NONE)
        if (mode != CoinJoinMode.NONE && this.mode == CoinJoinMode.NONE) {
            updateBalance(walletDataProvider.wallet!!.getBalance(Wallet.BalanceType.AVAILABLE))
        }
        updateState(mode, hasAnonymizableBalance, networkStatus, blockChain)
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
            message: PoolMessage?
        ) {
            super.onSessionStarted(wallet, sessionId, denomination, message)
            log.info("Session {} started.  {}% mixed", sessionId, progress)
        }

        override fun onSessionComplete(
            wallet: WalletEx?,
            sessionId: Int,
            denomination: Int,
            state: PoolState?,
            message: PoolMessage?,
            address: MasternodeAddress?,
            joined: Boolean
        ) {
            super.onSessionComplete(wallet, sessionId, denomination, state, message, address, joined)
            // TODO: _progressFlow.emit(progress)
            log.info("Session {} complete. {} % mixed -- {}", sessionId, progress, message)
        }
    }

    var encryptionKey: KeyParameter? = null
    private fun encryptionKeyParameter(): KeyParameter {
        if (encryptionKey == null) {
            encryptionKey = platformRepo.getWalletEncryptionKey() ?: throw IllegalStateException(
                "cannot obtain wallet encryption key"
            )
        }
        return encryptionKey!!
    }

    private fun decryptKey(key: ECKey): ECKey {
        val watch = Stopwatch.createStarted()
        val decryptedKey = key.decrypt(encryptionKeyParameter())
        log.info("Decrypting key took {}", watch.elapsed(TimeUnit.MILLISECONDS))
        return decryptedKey
    }

    override suspend fun configureMixing(restoreFromConfig: Boolean) {
        configureMixing(
            Coin.valueOf(1000, 0),
            { encryptionKeyParameter() },
            { decryptKey(it) },
            restoreFromConfig
        )
    }

    override suspend fun configureMixing(
        amount: Coin,
        requestKeyParameter: RequestKeyParameter,
        requestDecryptedKey: RequestDecryptedKey,
        restoreFromConfig: Boolean
    ) {
        if (restoreFromConfig) {
            // read from data store
            val amountToMix = config.get(CoinJoinConfig.COINJOIN_AMOUNT)
            val rounds = config.get(CoinJoinConfig.COINJOIN_ROUNDS)
            val sessions = 4; //config.get(CoinJoinConfig.COINJOIN_SESSIONS)
            val isMultiSession = false //config.get(CoinJoinConfig.COINJOIN_MULTISESSION)
            // set client options
            CoinJoinClientOptions.setRounds(rounds ?: DEFAULT_ROUNDS)
            CoinJoinClientOptions.setSessions(sessions ?: DEFAULT_SESSIONS)
            //CoinJoinClientOptions.setAmount(Coin.valueOf(1000, 0)) // amountToMix?.let { Coin.valueOf(amountToMix) } ?: amount)
            CoinJoinClientOptions.setMultiSessionEnabled(isMultiSession ?: DEFAULT_MULTISESSION)
        } else {
            CoinJoinClientOptions.setSessions(DEFAULT_SESSIONS)
            CoinJoinClientOptions.setAmount(max(FAST_MIXING_DASHPAY_FEE, amount))
            CoinJoinClientOptions.setMultiSessionEnabled(DEFAULT_MULTISESSION)

            when (mode) {
                CoinJoinMode.NONE -> CoinJoinClientOptions.setAmount(Coin.ZERO)
                CoinJoinMode.INTERMEDIATE -> CoinJoinClientOptions.setRounds(DEFAULT_ROUNDS)
                CoinJoinMode.ADVANCED -> CoinJoinClientOptions.setRounds(DEFAULT_ROUNDS * 2)
            }
            // save to data store
            //config.set(CoinJoinConfig.COINJOIN_AMOUNT, CoinJoinClientOptions.getAmount().value)
            config.set(CoinJoinConfig.COINJOIN_ROUNDS, CoinJoinClientOptions.getRounds())
            config.set(CoinJoinConfig.COINJOIN_SESSIONS, CoinJoinClientOptions.getSessions())
            config.set(CoinJoinConfig.COINJOIN_MULTISESSION, CoinJoinClientOptions.isMultiSessionEnabled())
        }
        FAST_MIXING_DENOMINATIONS_REMOVE.forEach {
            CoinJoinClientOptions.removeDenomination(it)
        }
        // TODO: have CoinJoinClientOptions.toString instead do this
        log.info(
            "mixing configuration:  { rounds: ${CoinJoinClientOptions.getRounds()}, sessions: ${CoinJoinClientOptions.getSessions()}, amount: ${amount.toFriendlyString()}}"
        )
        coinJoinManager?.run {
            setRequestKeyParameter(requestKeyParameter)
            setRequestDecryptedKey(requestDecryptedKey)
        }
    }

    @Deprecated("no longer required")
    suspend fun prepareAndStartMixing() {
        log.info("coinjoin: prepare and start mixing...")
        // do we need to mix?
        val wallet = walletDataProvider.wallet!! as WalletEx
        Context.propagate(wallet.context)
        // the mixed balance must meet the getAmount() requirement and all denominated coins must be mixed
        val mixedAmount = wallet.coinJoinBalance
        val denominatedAmount = wallet.denominatedBalance
        if (wallet.anonymizableBalance.isGreaterThan(Coin.ZERO)) {
            log.info(
                "coinjoin: mixing is complete $mixedAmount/$denominatedAmount of ${CoinJoinClientOptions.getAmount()}"
            )
        } else {
            log.info("coinjoin: start the mixing process...")
        }
    }

    private suspend fun prepareMixing() {
        log.info("coinjoin: Mixing preparation began")
        clear()
        val wallet = walletDataProvider.wallet!!
        addMixingCompleteListener(mixingProgressTracker)
        addSessionCompleteListener(mixingProgressTracker)
        coinJoinManager?.run {
            clientManager = CoinJoinClientManager(wallet)
            coinJoinClientManagers[wallet.description] = clientManager
            // this allows mixing to wait for the last transaction to be confirmed
            //clientManager.addContinueMixingOnError(PoolStatus.ERR_NO_INPUTS)
            // wait until the masternode sync system fixes itself
            clientManager.addContinueMixingOnError(PoolStatus.ERR_NO_MASTERNODES_DETECTED)
            clientManager.setStopOnNothingToDo(true)
            val mixingFinished = clientManager.mixingFinishedFuture

            val mixingCompleteListener =
                MixingCompleteListener { _, statusList ->
                    statusList?.let {
                        for (status in it) {
                            if (status != PoolStatus.FINISHED && status != PoolStatus.ERR_NOT_ENOUGH_FUNDS && status != PoolStatus.ERR_NO_INPUTS) {
                                coroutineScope.launch { updateMixingState(MixingStatus.ERROR) }
                                exception = Exception("Mixing stopped before completion ${status.name}")
                                return@let
                            }
                        }
                    }
                }

            val sessionCompleteListener = SessionCompleteListener { _, _, _, _, _, _, _ ->
                coroutineScope.launch {
                    updateProgress()
                }
            }

            mixingFinished.addListener({
                log.info("Mixing complete.")
                removeMixingCompleteListener(mixingCompleteListener)
                removeSessionCompleteListener(sessionCompleteListener)
                if (mixingFinished.get()) {
                    coroutineScope.launch {
                        updateProgress()
                        updateMixingState(MixingStatus.FINISHED)
                    }
                } else {
                    coroutineScope.launch {
                        updateProgress()
                        updateMixingState(MixingStatus.PAUSED)
                    }
                }
            }, Threading.USER_THREAD)

            addMixingCompleteListener(Threading.USER_THREAD, mixingCompleteListener)
            addSessionCompleteListener(Threading.USER_THREAD, sessionCompleteListener)
            log.info("coinjoin: mixing preparation finished")
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
            log.info(
                "Mixing " + if (result) "started successfully" else "start failed: " + clientManager.statuses + ", will retry"
            )
            true
        }
    }

    private fun stopMixing() {
        log.info("Mixing process will stop...")
        if (coinJoinManager == null || !this::clientManager.isInitialized) {
            return
        }

        encryptionKey = null

        // if mixing is not complete, then tell the future we didn't finish yet
        if (!clientManager.mixingFinishedFuture.isDone) {
            clientManager.mixingFinishedFuture.set(false)
        }
        // remove all listeners
        mixingCompleteListeners.forEach { coinJoinManager?.removeMixingCompleteListener(it) }
        sessionCompleteListeners.forEach { coinJoinManager?.removeSessionCompleteListener(it) }
        coinJoinManager?.stop()
    }

    private fun setBlockchain(blockChain: AbstractBlockChain?) {
        if (blockChain != this.blockChain) {
            log.info("setting blockchain in clientManager to ${blockChain?.chainHead?.height ?: "null"}")
            this.blockChain = blockChain
        }

        this.blockChain = blockChain
    }

    private fun addSessionCompleteListener(sessionCompleteListener: SessionCompleteListener) {
        sessionCompleteListeners.add(sessionCompleteListener)
        coinJoinManager?.addSessionCompleteListener(Threading.USER_THREAD, sessionCompleteListener)
    }

    private fun addMixingCompleteListener(mixingCompleteListener: MixingCompleteListener) {
        mixingCompleteListeners.add(mixingCompleteListener)
        coinJoinManager?.addMixingCompleteListener(Threading.USER_THREAD, mixingCompleteListener)
    }

    /** clear previous state */
    private fun clear() {
        exception = null
    }

    private suspend fun updateProgress() {
        val wallet = walletDataProvider.wallet as WalletEx
        val mixedBalance = wallet.coinJoinBalance
        val anonymizableBalance = wallet.getAnonymizableBalance(false, false)
        if (mixedBalance != Coin.ZERO && anonymizableBalance != Coin.ZERO) {
            val progress = mixedBalance.value * 100.0 / (mixedBalance.value + anonymizableBalance.value)
            log.info("coinjoin: progress {}", progress)
            _progressFlow.emit(progress)
        }
    }
}
