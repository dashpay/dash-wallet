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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.google.common.base.Stopwatch
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.getTimeSkew
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
import org.bitcoinj.coinjoin.listeners.CoinJoinTransactionListener
import org.bitcoinj.coinjoin.listeners.MixingCompleteListener
import org.bitcoinj.coinjoin.listeners.SessionCompleteListener
import org.bitcoinj.coinjoin.listeners.SessionStartedListener
import org.bitcoinj.coinjoin.progress.MixingProgressTracker
import org.bitcoinj.coinjoin.utils.CoinJoinManager
import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.MasternodeAddress
import org.bitcoinj.core.Transaction
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

/**
 * CoinJoin Service
 *
 * Monitor the status of the CoinJoin Mixing Service
 */
interface CoinJoinService {
    fun observeActiveSessions(): Flow<Int>
    suspend fun getMixingState(): MixingStatus
    fun observeMixingState(): Flow<MixingStatus>
    suspend fun getMixingProgress(): Double
    fun observeMixingProgress(): Flow<Double>
    fun updateTimeSkew(timeSkew: Long)
}

enum class MixingStatus {
    NOT_STARTED, // Mixing has not begun or CoinJoinMode is NONE
    MIXING, // Mixing is underway
    PAUSED, // Mixing is not finished, but is blocked by network connectivity
    FINISHED, // The entire balance has been mixed
    ERROR // An error stopped the mixing process
}

const val MAX_ALLOWED_AHEAD_TIMESKEW = 5000L // 5 seconds
const val MAX_ALLOWED_BEHIND_TIMESKEW = 20000L // 20 seconds

@Singleton
class CoinJoinMixingService @Inject constructor(
    @ApplicationContext private val context: Context,
    val walletDataProvider: WalletDataProvider,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val config: CoinJoinConfig,
    private val platformRepo: PlatformRepo
) : CoinJoinService {

    companion object {
        val log: Logger = LoggerFactory.getLogger(CoinJoinMixingService::class.java)
        const val DEFAULT_MULTISESSION = false // for stability, need to investigate
        const val DEFAULT_ROUNDS = 4
        const val DEFAULT_SESSIONS = 4
        const val DEFAULT_DENOMINATIONS_GOAL = 50
        const val DEFAULT_DENOMINATIONS_HARDCAP = 300

        // these are not for production
        val FAST_MIXING_DENOMINATIONS_REMOVE = listOf<Denomination>() // Denomination.THOUSANDTH

        fun isInsideTimeSkewBounds(timeSkew: Long): Boolean {
            return if (timeSkew > 0) {
                timeSkew < MAX_ALLOWED_AHEAD_TIMESKEW
            } else {
                (-timeSkew) < MAX_ALLOWED_BEHIND_TIMESKEW
            }
        }
    }

    private val coinJoinManager: CoinJoinManager?
        get() = walletDataProvider.wallet?.context?.coinJoinManager
    private lateinit var clientManager: CoinJoinClientManager

    private var mixingCompleteListeners: ArrayList<MixingCompleteListener> = arrayListOf()
    private var sessionCompleteListeners: ArrayList<SessionCompleteListener> = arrayListOf()
    private var sessionStartedListeners: ArrayList<SessionStartedListener> = arrayListOf()

    var mode: CoinJoinMode = CoinJoinMode.NONE
    private val _mixingState = MutableStateFlow(MixingStatus.NOT_STARTED)
    private val _progressFlow = MutableStateFlow(0.00)

    override suspend fun getMixingState(): MixingStatus = _mixingState.value
    override fun observeMixingState(): Flow<MixingStatus> = _mixingState

    private val executor = Executors.newFixedThreadPool(2, ContextPropagatingThreadFactory("coinjoin-pool"))
    private val coroutineScope = CoroutineScope(executor.asCoroutineDispatcher())

    private val uiCoroutineScope = CoroutineScope(Dispatchers.Main)

    private var blockChain: AbstractBlockChain? = null
    private val isBlockChainSet: Boolean
        get() = blockChain != null
    private var networkStatus: NetworkStatus = NetworkStatus.UNKNOWN
    private var isSynced = false
    private var hasAnonymizableBalance: Boolean = false
    private var timeSkew: Long = 0L
    private var activeSessions = 0
    private val activeSessionsFlow = MutableStateFlow(0)

    // https://stackoverflow.com/questions/55421710/how-to-suspend-kotlin-coroutine-until-notified
    private val updateMutex = Mutex(locked = false)
    private val updateMixingStateMutex = Mutex(locked = false)
    private var exception: Throwable? = null

    private val timeChangeReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIME_CHANGED) {
                // Time has changed, handle the change here
                log.info("Time or Time Zone changed")
                coroutineScope.launch {
                    updateTimeSkewInternal(getTimeSkew())
                }
            }
        }
    }

    override fun observeMixingProgress(): Flow<Double> = _progressFlow

    init {
        blockchainStateProvider.observeNetworkStatus()
            .filterNot { it == NetworkStatus.UNKNOWN }
            .onEach { status ->
                updateNetworkStatus(status)
            }
            .launchIn(coroutineScope)

        blockchainStateProvider.observeBlockChain()
            .onEach { blockChain ->
                updateBlockChain(blockChain)
            }
            .launchIn(coroutineScope)

        blockchainStateProvider.observeState()
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { blockChainState ->
                val isSynced = blockChainState.isSynced()
                if (isSynced != this.isSynced) {
                    val networkStatus = blockchainStateProvider.getNetworkStatus()
                    updateState(config.getMode(), timeSkew, hasAnonymizableBalance, networkStatus, isSynced, blockChain)
                }
                // this will trigger mixing as new blocks are mined and received tx's are confirmed
                if (isSynced) {
                    log.info("coinjoin: new block: ${blockChainState.bestChainHeight}")
                    updateBalance(walletDataProvider.getWalletBalance())
                }
            }
            .launchIn(coroutineScope)

        walletDataProvider.observeBalance()
            .distinctUntilChanged()
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

    /** updates timeSkew in #[coroutineScope] */
    override fun updateTimeSkew(timeSkew: Long) {
        coroutineScope.launch {
            updateTimeSkewInternal(timeSkew)
        }
    }

    suspend fun getCurrentTimeSkew(): Long {
        return try {
            getTimeSkew()
        } catch (e: Exception) {
            log.info("timeshew problem", e)
            return 0L
        }
    }

    suspend fun updateTimeSkewInternal(timeSkew: Long) {
        updateState(config.getMode(), timeSkew, hasAnonymizableBalance, networkStatus, isSynced, blockChain)
    }

    private suspend fun updateBalance(balance: Coin) {
        CoinJoinClientOptions.setAmount(balance)
        log.info("coinjoin: total balance: ${balance.toFriendlyString()}")
        val walletEx = walletDataProvider.wallet as WalletEx
        log.info("coinjoin: mixed balance: ${walletEx.coinJoinBalance.toFriendlyString()}")
        val anonBalance = walletEx.getAnonymizableBalance(false, false)
        log.info("coinjoin: anonymizable balance {}", anonBalance.toFriendlyString())
        val hasPartiallyMixedCoins = (walletEx.denominatedBalance - walletEx.coinJoinBalance).isGreaterThan(Coin.ZERO)
        val hasAnonymizableBalance = anonBalance.isGreaterThan(CoinJoin.getSmallestDenomination())

        val canDenominate = if (this::clientManager.isInitialized) {
            clientManager.doAutomaticDenominating(true)
        } else {
            // if the client manager is not running, just say canDenominate is true
            // The first round of execution will determine if mixing can happen
            log.info("coinjoin: client manager is not running, canDemoninate=true")
            true
        }

        val hasBalanceLeftToMix = when {
            hasPartiallyMixedCoins -> true
            hasAnonymizableBalance && canDenominate -> true
            else -> false
        }

        log.info(
            "coinjoin: mixing can occur: $hasBalanceLeftToMix = balance: (${anonBalance.isGreaterThan(
                CoinJoin.getSmallestDenomination()
            )} && canDenominate: $canDenominate) || partially-mixed: $hasPartiallyMixedCoins"
        )
        updateState(
            config.getMode(),
            getCurrentTimeSkew(),
            hasBalanceLeftToMix,
            networkStatus,
            isSynced,
            blockchainStateProvider.getBlockChain()
        )
    }

    private suspend fun updateState(
        mode: CoinJoinMode,
        timeSkew: Long,
        hasAnonymizableBalance: Boolean,
        networkStatus: NetworkStatus,
        isSynced: Boolean,
        blockChain: AbstractBlockChain?
    ) {
        updateMutex.lock()
        log.info(
            "coinjoin-old-state: ${this.mode}, ${this.timeSkew}ms, ${this.hasAnonymizableBalance}, ${this.networkStatus}, synced: ${this.isSynced} ${blockChain != null}"
        )
        try {
            setBlockchain(blockChain)
            log.info(
                "coinjoin-new-state: $mode, $timeSkew ms, $hasAnonymizableBalance, $networkStatus, synced: $isSynced, ${blockChain != null}"
            )
            // log.info("coinjoin-Current timeskew: ${getCurrentTimeSkew()}")
            this.networkStatus = networkStatus
            this.hasAnonymizableBalance = hasAnonymizableBalance
            this.isSynced = isSynced
            this.mode = mode
            this.timeSkew = timeSkew

            if (mode == CoinJoinMode.NONE || !isInsideTimeSkewBounds(timeSkew)) {
                updateMixingState(MixingStatus.NOT_STARTED)
            } else {
                configureMixing()
                if (hasAnonymizableBalance) {
                    if (networkStatus == NetworkStatus.CONNECTED && isBlockChainSet && isSynced) {
                        updateMixingState(MixingStatus.MIXING)
                    } else {
                        updateMixingState(MixingStatus.PAUSED)
                    }
                } else {
                    updateMixingState(MixingStatus.FINISHED)
                }
            }
            updateProgress()
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
            val previousMixingStatus = _mixingState.value
            _mixingState.value = mixingStatus
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
                }
            }
        } finally {
            updateMixingStateMutex.unlock()
        }
    }

    private suspend fun updateBlockChain(blockChain: AbstractBlockChain?) {
        updateState(mode, timeSkew, hasAnonymizableBalance, networkStatus, isSynced, blockChain)
    }

    private suspend fun updateNetworkStatus(networkStatus: NetworkStatus) {
        updateState(mode, timeSkew, hasAnonymizableBalance, networkStatus, isSynced, blockChain)
    }

    private suspend fun updateMode(mode: CoinJoinMode) {
        CoinJoinClientOptions.setEnabled(mode != CoinJoinMode.NONE)
        if (mode != CoinJoinMode.NONE && this.mode == CoinJoinMode.NONE) {
            configureMixing()
        }
        updateBalance(walletDataProvider.wallet!!.getBalance(Wallet.BalanceType.AVAILABLE))
        val currentTimeSkew = getCurrentTimeSkew()
        updateState(mode, currentTimeSkew, hasAnonymizableBalance, networkStatus, isSynced, blockChain)
    }

    private var mixingProgressTracker: MixingProgressTracker = object : MixingProgressTracker() {
        override fun onMixingComplete(wallet: WalletEx?, statusList: MutableList<PoolStatus>?) {
            super.onMixingComplete(wallet, statusList)
            log.info("Mixing Complete.  {}% mixed", progress)
        }

        override fun onSessionStarted(
            wallet: WalletEx?,
            sessionId: Int,
            denomination: Int,
            message: PoolMessage?
        ) {
            super.onSessionStarted(wallet, sessionId, denomination, message)
            log.info("Session started: {}.  {}% mixed. {} active sessions", sessionId, progress, activeSessions + 1)
            updateActiveSessions()
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
            log.info("Session completed: {}.  {}% mixed. {} active sessions", sessionId, progress, activeSessions - 1)
            updateActiveSessions()
        }

        override fun onTransactionProcessed(tx: Transaction?, type: CoinJoinTransactionType?, sessionId: Int) {
            super.onTransactionProcessed(tx, type, sessionId)
            log.info("coinjoin-tx {} in session {}  {}", type, sessionId, tx?.txId)
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

    private val requestKeyParameter = RequestKeyParameter { encryptionKeyParameter() }
    private val requestDecryptedKey = RequestDecryptedKey { decryptKey(it) }

    private fun configureMixing() {
        configureMixing(walletDataProvider.getWalletBalance())
    }

    /** set CoinJoinClientOptions based on CoinJoinMode */
    private fun configureMixing(amount: Coin) {
        when (mode) {
            CoinJoinMode.NONE -> {
                // no options to set
            }
            CoinJoinMode.INTERMEDIATE -> {
                CoinJoinClientOptions.setRounds(DEFAULT_ROUNDS)
                (walletDataProvider.wallet as WalletEx).coinJoin.setRounds(DEFAULT_ROUNDS)
            }
            CoinJoinMode.ADVANCED -> {
                CoinJoinClientOptions.setRounds(DEFAULT_ROUNDS * 2)
                (walletDataProvider.wallet as WalletEx).coinJoin.setRounds(DEFAULT_ROUNDS * 2)
            }
        }

        CoinJoinClientOptions.setSessions(DEFAULT_SESSIONS)
        CoinJoinClientOptions.setMultiSessionEnabled(DEFAULT_MULTISESSION)
        CoinJoinClientOptions.setDenomsGoal(DEFAULT_DENOMINATIONS_GOAL)
        CoinJoinClientOptions.setDenomsHardCap(DEFAULT_DENOMINATIONS_HARDCAP)

        FAST_MIXING_DENOMINATIONS_REMOVE.forEach {
            CoinJoinClientOptions.removeDenomination(it)
        }

        // TODO: have CoinJoinClientOptions.toString instead do this
        log.info(
            "mixing configuration:  { rounds: ${CoinJoinClientOptions.getRounds()}, sessions: ${CoinJoinClientOptions.getSessions()}, amount: ${amount.toFriendlyString()}, multisession: ${CoinJoinClientOptions.isMultiSessionEnabled()}}"
        )
    }

    private suspend fun prepareMixing() {
        log.info("coinjoin: Mixing preparation began")
        clear()
        val wallet = walletDataProvider.wallet!!
        coinJoinManager?.run {
            clientManager = CoinJoinClientManager(wallet)
            coinJoinClientManagers[wallet.description] = clientManager
            // this allows mixing to wait for the last transaction to be confirmed
            // clientManager.addContinueMixingOnError(PoolStatus.ERR_NO_INPUTS)
            // wait until the masternode sync system fixes itself
            clientManager.addContinueMixingOnError(PoolStatus.ERR_NO_MASTERNODES_DETECTED)
            clientManager.setStopOnNothingToDo(true)
            val mixingFinished = clientManager.mixingFinishedFuture

            addMixingCompleteListener(executor, mixingProgressTracker)
            addSessionStartedListener(executor, mixingProgressTracker)
            addSessionCompleteListener(executor, mixingProgressTracker)
            addTransationListener(executor, mixingProgressTracker)

            val mixingCompleteListener =
                MixingCompleteListener { _, statusList ->
                    statusList?.let {
                        for (status in it) {
                            if (status != PoolStatus.FINISHED &&
                                status != PoolStatus.ERR_NOT_ENOUGH_FUNDS &&
                                status != PoolStatus.ERR_NO_INPUTS
                            ) {
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

            addMixingCompleteListener(executor, mixingCompleteListener)
            addSessionCompleteListener(executor, sessionCompleteListener)
            log.info("coinjoin: mixing preparation finished")

            setRequestKeyParameter(requestKeyParameter)
            setRequestDecryptedKey(requestDecryptedKey)
            start()
        }
    }

    private suspend fun startMixing(): Boolean {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        context.registerReceiver(timeChangeReceiver, filter)
        clientManager.setBlockChain(blockChain)
        return if (!clientManager.startMixing()) {
            log.info("Mixing has been started already.")
            false
        } else {
            // run this on a different thread?
            val asyncStart = coroutineScope.async(Dispatchers.IO) {
                // though coroutineScope is on a Context propogated thread, we still need this
                org.bitcoinj.core.Context.propagate(walletDataProvider.wallet!!.context)
                coinJoinManager?.initMasternodeGroup(blockChain)
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
        if (coinJoinManager == null || !this::clientManager.isInitialized) {
            return
        }
        log.info("Mixing process will stop...")
        encryptionKey = null

        // if mixing is not complete, then tell the future we didn't finish yet
        if (!clientManager.mixingFinishedFuture.isDone) {
            clientManager.mixingFinishedFuture.set(false)
        }
        // remove all listeners
        mixingCompleteListeners.forEach { coinJoinManager?.removeMixingCompleteListener(it) }
        sessionCompleteListeners.forEach { coinJoinManager?.removeSessionCompleteListener(it) }
        sessionStartedListeners.forEach { coinJoinManager?.removeSessionStartedListener(it) }
        clientManager.stopMixing()
        coinJoinManager?.stop()
        context.unregisterReceiver(timeChangeReceiver)
    }

    private fun setBlockchain(blockChain: AbstractBlockChain?) {
        if (blockChain != this.blockChain) {
            log.info("setting blockchain in clientManager to ${blockChain?.chainHead?.height ?: "null"}")
            this.blockChain = blockChain
        }

        this.blockChain = blockChain
    }

    private fun addSessionStartedListener(sessionStartedListener: SessionStartedListener) {
        sessionStartedListeners.add(sessionStartedListener)
        coinJoinManager?.addSessionStartedListener(Threading.USER_THREAD, sessionStartedListener)
    }
    private fun addSessionCompleteListener(sessionCompleteListener: SessionCompleteListener) {
        sessionCompleteListeners.add(sessionCompleteListener)
        coinJoinManager?.addSessionCompleteListener(Threading.USER_THREAD, sessionCompleteListener)
    }

    private fun addMixingCompleteListener(mixingCompleteListener: MixingCompleteListener) {
        mixingCompleteListeners.add(mixingCompleteListener)
        coinJoinManager?.addMixingCompleteListener(Threading.USER_THREAD, mixingCompleteListener)
    }

    private fun addTransationListener(sessionCompleteListener: CoinJoinTransactionListener) {
        coinJoinManager?.addTransationListener(Threading.USER_THREAD, sessionCompleteListener)
    }

    /** clear previous state */
    private fun clear() {
        exception = null
    }

    override suspend fun getMixingProgress(): Double {
        val wallet = walletDataProvider.wallet as WalletEx
        return wallet.coinJoin.mixingProgress * 100.0
    }

    private suspend fun updateProgress() {
        val progress = getMixingProgress()
        _progressFlow.emit(progress)
    }

    private fun updateActiveSessions() {
        coroutineScope.launch {
            activeSessions = if (this@CoinJoinMixingService::clientManager.isInitialized) {
                clientManager.sessionsStatus?.count { poolStatus ->
                    poolStatus == PoolStatus.CONNECTING || poolStatus == PoolStatus.CONNECTED || poolStatus == PoolStatus.MIXING
                } ?: 0
            } else {
                0
            }
            log.info("coinjoin-activeSessions: {}", activeSessions)
            activeSessionsFlow.emit(activeSessions)
        }
    }
    override fun observeActiveSessions(): Flow<Int> = activeSessionsFlow
}
