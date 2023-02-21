package de.schildbach.wallet.service

import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
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

interface CoinJoinService {
    fun needsToMix(amount: Coin): Boolean
    fun configureMixing(
        amount: Coin,
        requestKeyParameter: RequestKeyParameter,
        requestDecryptedKey: RequestDecryptedKey
    )

    suspend fun prepareAndStartMixing()
    fun startMixing(): Boolean
    fun stopMixing()
    fun getMixingStatus(): MixingStatus
    suspend fun waitForMixing()
    suspend fun waitForMixingWithException()
}

enum class MixingStatus {
    NOT_STARTED,
    MIXING,
    PAUSED,
    FINISHED,
    ERROR
}

class CoinJoinMixingService @Inject constructor(
    val walletDataProvider: WalletDataProvider,
    val walletApplication: WalletApplication,
    val blockchainStateProvider: BlockchainStateProvider
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

    private val syncScope = CoroutineScope(
        Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    )

    private var blockChain: AbstractBlockChain? = null
    private var isBlockChainSet = false
    private var networkStatus: NetworkStatus = NetworkStatus.STOPPED

    // https://stackoverflow.com/questions/55421710/how-to-suspend-kotlin-coroutine-until-notified            //mixingStatus = MixingStatus.MIXING

    private val mixingMutex = Mutex(locked = true)
    private var exception: Throwable? = null
    override suspend fun waitForMixing() = mixingMutex.withLock{}
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
            .onEach { status ->
                updateNetworkStatus(status)
            }
            .launchIn(syncScope)

        blockchainStateProvider.observeBlockChain()
            .filterNotNull()
            .onEach { blockChain ->
                updateBlockChain(blockChain)
            }
            .launchIn(syncScope)
    }

    private suspend fun updateState(mixingStatus: MixingStatus, networkStatus: NetworkStatus, blockChain: AbstractBlockChain?) {

        log.info("coinjoin-updateState: $mixingStatus, $networkStatus, ${blockChain != null}")
        setBlockchain(blockChain)

        when {
            mixingStatus == MixingStatus.MIXING && networkStatus == NetworkStatus.CONNECTED -> {
                prepareAndStartMixing2()
                setBlockchain(blockChain)
                startMixing()
            }
            this.mixingStatus == MixingStatus.MIXING && mixingStatus == MixingStatus.FINISHED -> {
                stopMixing()
                setMixingComplete()
            }
            mixingStatus == MixingStatus.MIXING && this.networkStatus == NetworkStatus.CONNECTED && networkStatus == NetworkStatus.NOT_AVAILABLE -> {
                stopMixing()
            }
            mixingStatus == MixingStatus.PAUSED && this.networkStatus == NetworkStatus.NOT_AVAILABLE && networkStatus == NetworkStatus.CONNECTED -> {
                startMixing()
            }
            mixingStatus == MixingStatus.ERROR -> setMixingComplete()
        }
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
        updateState(mixingStatus, networkStatus, blockChain);
    }

    private var mixingProgressTracker: MixingProgressTracker = object : MixingProgressTracker() {
        override fun onMixingComplete(wallet: WalletEx?, statusList: MutableList<PoolStatus>?) {
            super.onMixingComplete(wallet, statusList)
            log.info("Mixing Complete.  {}% mixed", progress)
            //TODO: _progressFlow.emit(progress)
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
            message: PoolMessage?
        ) {
            super.onSessionComplete(wallet, sessionId, denomination, message)
            //TODO: _progressFlow.emit(progress)
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

    override fun configureMixing(
        amount: Coin,
        requestKeyParameter: RequestKeyParameter,
        requestDecryptedKey: RequestDecryptedKey
    ) {
        CoinJoinClientOptions.setRounds(1);
        CoinJoinClientOptions.setSessions(4);
        CoinJoinClientOptions.setAmount(amount)
        CoinJoinClientOptions.setMultiSessionEnabled(false)
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
        if (wallet.coinJoinBalance.isGreaterThanOrEqualTo(CoinJoinClientOptions.getAmount())) {
            setMixingComplete()
        } else {
            updateMixingStatus(MixingStatus.MIXING)
        }
    }

    private suspend fun prepareAndStartMixing2() {
        CoinJoinClientOptions.setEnabled(true);
        val wallet = walletDataProvider.wallet!!
        addMixingCompleteListener(mixingProgressTracker)
        addSessionCompleteListener(mixingProgressTracker)
        coinJoinManager?.run {
            clientManager = CoinJoinClientManager(wallet)
            coinJoinClientManagers[wallet.description] = clientManager
            clientManager.setStopOnNothingToDo(true)
            val mixingFinished = clientManager.mixingFinishedFuture

            val mixingCompleteListener =
                MixingCompleteListener { _, statusList ->
                    statusList?.let {
                        for (status in it) {
                            if (status != PoolStatus.FINISHED) {
                                syncScope.launch { updateMixingStatus(MixingStatus.ERROR) }
                                exception = Exception("Mixing stopped before completion ${status.name}")
                            }
                        }
                    }
                }

            mixingFinished.addListener({
                log.info("Mixing complete.")
                removeMixingCompleteListener(mixingCompleteListener)
                if (mixingFinished.get()) {
                    syncScope.launch { updateMixingStatus(MixingStatus.FINISHED) }
                } else {
                    syncScope.launch { updateMixingStatus(MixingStatus.PAUSED) }
                }
            }, Threading.SAME_THREAD)

            addMixingCompleteListener(Threading.SAME_THREAD, mixingCompleteListener)
        }
    }

    override fun startMixing(): Boolean {
        Context.propagate(walletDataProvider.wallet!!.context)
        return if (!clientManager.startMixing()) {
            log.info("Mixing has been started already.")
            false
        } else {
            val result = clientManager.doAutomaticDenominating()
            log.info("Mixing " + if (result) "started successfully" else "start failed: " + clientManager.statuses + ", will retry")
            true
        }
    }

    override fun stopMixing() {
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
        isBlockChainSet = false
        CoinJoinClientOptions.setEnabled(false)
    }

    private fun setBlockchain(blockChain: AbstractBlockChain?) {
        if (blockChain != null && !isBlockChainSet && this::clientManager.isInitialized) {
            log.info("setting blockchain in clientManager")
            clientManager.setBlockChain(blockChain)
            isBlockChainSet = true
        } else {
            log.info("setting blockchain in clientManager: only in class")
        }
        this.blockChain = blockChain
    }

    fun addSessionCompleteListener(sessionCompleteListener: SessionCompleteListener) {
        sessionCompleteListeners.add(sessionCompleteListener);
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