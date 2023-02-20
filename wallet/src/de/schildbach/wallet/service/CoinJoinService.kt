package de.schildbach.wallet.service

import android.content.Intent
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface CoinJoinService {
    fun needsToMix(amount: Coin): Boolean
    fun configureMixing(amount: Coin, requestKeyParameter: RequestKeyParameter, requestDecryptedKey: RequestDecryptedKey)
    suspend fun prepareAndStartMixing(): Boolean
    fun prepareAndStartMixingAsync()
    fun startMixing(): Boolean
    fun stopMixing()
    fun setBlockchain(blockChain: AbstractBlockChain)
    fun getMixingStatus(): MixingStatus
    // TODO: add getMixingProgress() : Flow<progress data>
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
     val blockchainStateDao: BlockchainStateDao
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

    init {
            blockchainStateDao.observeState()
                .filterNotNull()
                .onEach { state ->
                    //updateSyncStatus(state)
                    //updatePercentage(state)
                }
                .launchIn(syncScope)

    }

    private var mixingProgressTracker: MixingProgressTracker = object : MixingProgressTracker() {
        override fun onMixingComplete(wallet: WalletEx?, statusList: MutableList<PoolStatus>?) {
            super.onMixingComplete(wallet, statusList)
            log.info("Mixing Complete.  {}%", progress)

            //TODO: _progressFlow.emit(progress)
            triggerStopMixing()
        }

        override fun onSessionStarted(
            wallet: WalletEx?,
            sessionId: Int,
            denomination: Int,
            message: PoolMessage?
        ) {
            super.onSessionStarted(wallet, sessionId, denomination, message)
            log.info("Session {} started.  {}%", sessionId, progress)
        }

        override fun onSessionComplete(
            wallet: WalletEx?,
            sessionId: Int,
            denomination: Int,
            message: PoolMessage?
        ) {
            super.onSessionComplete(wallet, sessionId, denomination, message)
            //TODO: _progressFlow.emit(progress)
            log.info("Session {} complete.  {}% -- {}", sessionId, progress, message)
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
        return walletApplication.wallet?.getBalance(Wallet.BalanceType.COINJOIN_SPENDABLE)?.isLessThan(amount) ?: false
    }

    override fun configureMixing(amount: Coin, requestKeyParameter: RequestKeyParameter, requestDecryptedKey: RequestDecryptedKey) {
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

    override fun prepareAndStartMixingAsync() {
        syncScope.launch {
            prepareAndStartMixing()
        }
    }

    override suspend fun prepareAndStartMixing(): Boolean {
        CoinJoinClientOptions.setEnabled(true);
        val wallet = walletDataProvider.wallet!!
        addMixingCompleteListener(mixingProgressTracker)
        addSessionCompleteListener(mixingProgressTracker)
        coinJoinManager?.run {
            mixingStatus = MixingStatus.PAUSED
            clientManager = CoinJoinClientManager(wallet)
            coinJoinClientManagers[wallet.description] = clientManager
            clientManager.setStopOnNothingToDo(true)
            val mixingFinished = clientManager.mixingFinishedFuture
            return suspendCoroutine { continuation ->

                val mixingCompleteListener =
                    MixingCompleteListener { _, statusList ->
                        statusList?.let {
                            for (status in it) {
                                if (status != PoolStatus.FINISHED) {
                                    walletApplication.triggerStopMixing()
                                    mixingStatus = MixingStatus.ERROR
                                    continuation.resumeWithException(Exception("Mixing stopped before completion ${status.name}"))
                                }
                            }
                        }
                    }

                mixingFinished.addListener({
                    log.info("Mixing complete.")
                    triggerStopMixing()
                    removeMixingCompleteListener(mixingCompleteListener)
                    if (mixingFinished.get()) {
                        mixingStatus = MixingStatus.FINISHED
                        triggerCompleteMixing()
                        continuation.resumeWith(Result.success(true))
                    } else {
                        mixingStatus = MixingStatus.PAUSED
                        continuation.resumeWith(Result.success(false))//resumeWithException(Exception("Mixing stopped before completion"))
                    }
                }, Threading.SAME_THREAD)

                addMixingCompleteListener(Threading.SAME_THREAD, mixingCompleteListener)
                triggerMixing()
            }
        }
        return false
    }

    override fun startMixing(): Boolean {
        return if (!clientManager.startMixing()) {
            log.info("Mixing has been started already.")
            false
        } else {
            val result = clientManager.doAutomaticDenominating()
            log.info("Mixing " + if (result) "started successfully" else "start failed: " + clientManager.statuses + ", will retry")
            mixingStatus = MixingStatus.MIXING
            true
        }
    }

    override fun stopMixing() {
        log.info("Mixing process will stop...")
        if (coinJoinManager == null || !this::clientManager.isInitialized) {
            return
        }

        // is missing complete?
        if (!clientManager.mixingFinishedFuture.isDone) {
            clientManager.mixingFinishedFuture.set(false)
        }
        // remove all listeners
        mixingCompleteListeners.forEach { coinJoinManager?.removeMixingCompleteListener(it) }
        sessionCompleteListeners.forEach { coinJoinManager?.removeSessionCompleteListener(it) }
        coinJoinManager?.stop()
        CoinJoinClientOptions.setEnabled(false)
    }

    override fun setBlockchain(blockChain: AbstractBlockChain) {
        clientManager.setBlockChain(blockChain);
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

    private fun triggerMixing() {
        log.info("Mixing process will begin shortly...")
        walletApplication.startService(
            Intent(
                BlockchainService.ACTION_START_MIXING, null, walletApplication,
                BlockchainServiceImpl::class.java
            )
        )
    }

    private fun triggerStopMixing() {
        log.info("Mixing process will end...")
        walletApplication.startService(
            Intent(
                BlockchainService.ACTION_STOP_MIXING, null, walletApplication,
                BlockchainServiceImpl::class.java
            )
        )
    }

    private fun triggerCompleteMixing() {
        log.info("Mixing process will end...")
        walletApplication.startService(
            CreateIdentityService.createIntentForMixingComplete(walletApplication)
        )
    }
}