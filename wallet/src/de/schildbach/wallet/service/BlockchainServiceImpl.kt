/*
 * Copyright 2011-2024 the original author or authors.
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

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateUtils
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.common.base.Stopwatch
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletApplicationExt.clearDatabases
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.data.AddressBookProvider
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.ExchangeRatesDao
import de.schildbach.wallet.service.extensions.registerCrowdNodeConfirmedAddressFilter
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.OnboardingActivity.Companion.createIntent
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.dashpay.OnPreBlockProgressListener
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis.OfflineAnalyzer
import de.schildbach.wallet.util.BlockchainStateUtils
import de.schildbach.wallet.util.CrashReporter
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Block
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Coin
import org.bitcoinj.core.FilteredBlock
import org.bitcoinj.core.MasternodeSync
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.Utils
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener
import org.bitcoinj.core.listeners.PreBlocksDownloadListener
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.evolution.SimplifiedMasternodeListDiff
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.bitcoinj.evolution.listeners.MasternodeListDownloadedListener
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.net.discovery.MasternodePeerDiscovery
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import org.bitcoinj.net.discovery.PeerDiscovery
import org.bitcoinj.net.discovery.PeerDiscoveryException
import org.bitcoinj.net.discovery.SeedPeers
import org.bitcoinj.store.BlockStore
import org.bitcoinj.store.BlockStoreException
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.DefaultRiskAnalysis
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionUtils.getWalletAddressOfReceived
import org.dash.wallet.common.transactions.filters.NotFromAddressTxFilter
import org.dash.wallet.common.util.Constants.PREFIX_ALMOST_EQUAL_TO
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeAPIConfirmationHandler
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeDepositReceivedResponse
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeWithdrawalReceivedTx
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants.getCrowdNodeAddress
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.text.DecimalFormat
import java.util.Date
import java.util.EnumSet
import java.util.LinkedList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.max

/**
 * @author Andreas Schildbach
 * @author Eric Britten
 */
@AndroidEntryPoint
class BlockchainServiceImpl : LifecycleService(), BlockchainService {

    companion object {
        private const val MINIMUM_PEER_COUNT = 16
        private const val MIN_COLLECT_HISTORY = 2
        private const val IDLE_HEADER_TIMEOUT_MIN = 2
        private const val IDLE_MNLIST_TIMEOUT_MIN = 2
        private const val IDLE_BLOCK_TIMEOUT_MIN = 2
        private const val IDLE_TRANSACTION_TIMEOUT_MIN = 9
        private val MAX_HISTORY_SIZE = max(
            IDLE_TRANSACTION_TIMEOUT_MIN.toDouble(),
            IDLE_BLOCK_TIMEOUT_MIN.toDouble()
        ).toInt()
        private const val APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private const val BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private val TX_EXCHANGE_RATE_TIME_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(180)
        private val log = LoggerFactory.getLogger(BlockchainServiceImpl::class.java)
        const val START_AS_FOREGROUND_EXTRA = "start_as_foreground"
        var cleanupDeferred: CompletableDeferred<Unit>? = null
    }
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val onCreateCompleted = CompletableDeferred<Unit>()
    private var checkMutex = Mutex(false)

    @Inject lateinit var  application: WalletApplication

    @Inject lateinit var  config: Configuration

    @Inject lateinit var  walletUIConfig: WalletUIConfig

    @Inject lateinit var  notificationService: NotificationService

    @Inject lateinit var  crowdNodeBlockchainApi: CrowdNodeBlockchainApi

    @Inject lateinit var  crowdNodeConfig: CrowdNodeConfig

    @Inject lateinit var  blockchainStateDao: BlockchainStateDao

    @Inject lateinit var  exchangeRatesDao: ExchangeRatesDao

    @Inject lateinit var transactionMetadataProvider: TransactionMetadataProvider

    @Inject lateinit var platformSyncService: PlatformSyncService

    @Inject lateinit var  platformRepo: PlatformRepo

    @Inject lateinit var  packageInfoProvider: PackageInfoProvider

    @Inject lateinit var  connectivityManager: ConnectivityManager

    @Inject
    lateinit var blockchainStateDataProvider: BlockchainStateDataProvider

    @Inject
    lateinit var dashSystemService: DashSystemService

    @Inject lateinit var coinJoinService: CoinJoinService

    private var blockStore: BlockStore? = null
    private var headerStore: BlockStore? = null
    private var blockChainFile: File? = null
    private var headerChainFile: File? = null
    private var blockChain: BlockChain? = null
    private var headerChain: BlockChain? = null
    private var mnlistinfoBootStrapStream: InputStream? = null
    private var qrinfoBootStrapStream: InputStream? = null
    private var peerGroup: PeerGroup? = null
    private val handler = Handler()
    private val delayHandler = Handler()
    private var wakeLock: PowerManager.WakeLock? = null
    private var peerConnectivityListener: PeerConnectivityListener? = null
    private var nm: NotificationManager? = null
    private val impediments: MutableSet<Impediment> = EnumSet.noneOf(
        Impediment::class.java
    )
    private var blockchainState: BlockchainState? =
        BlockchainState(null, 0, false, impediments, 0, 0, 0)
    private var notificationCount = 0
    private var notificationAccumulatedAmount = Coin.ZERO
    private val notificationAddresses: MutableList<Address> = LinkedList()
    private val transactionsReceived = AtomicInteger()
    private val mnListDiffsReceived = AtomicInteger()
    private var serviceCreatedAt: Long = 0
    private var resetBlockchainOnShutdown = false
    private var deleteWalletFileOnShutdown = false

    //Settings to bypass dashj default dns seeds
    private val seedPeerDiscovery = SeedPeers(Constants.NETWORK_PARAMETERS)
    private val dnsDiscovery = DnsDiscovery(Constants.DNS_SEED, Constants.NETWORK_PARAMETERS)
    var peerDiscoveryList = ArrayList<PeerDiscovery>(2)
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var syncPercentage = 0 // 0 to 100%
    private var mixingStatus = MixingStatus.NOT_STARTED
    private var mixingProgress = 0.0
    private var balance = Coin.ZERO
    private var mixedBalance = Coin.ZERO
    private var foregroundService = ForegroundService.NONE
    private var pendingForegroundNotification: Notification? = null

    // Risk Analyser for Transactions that is PeerGroup Aware
    private var riskAnalyzer: AllowLockTimeRiskAnalysis.Analyzer? = null
    private var defaultRiskAnalyzer = DefaultRiskAnalysis.FACTORY
    private val crowdnodeFilters = listOf(
        NotFromAddressTxFilter(getCrowdNodeAddress(Constants.NETWORK_PARAMETERS)),
        CrowdNodeWithdrawalReceivedTx(Constants.NETWORK_PARAMETERS)
    )
    private val depositReceivedResponse =
        CrowdNodeDepositReceivedResponse(Constants.NETWORK_PARAMETERS)
    private var apiConfirmationHandler: CrowdNodeAPIConfirmationHandler? = null
    private fun handleMetadata(tx: Transaction) {
        serviceScope.launch {
            transactionMetadataProvider.syncTransaction(tx)
        }
    }

    private val walletEventListener: ThrottlingWalletChangeListener =
        object : ThrottlingWalletChangeListener(
            APPWIDGET_THROTTLE_MS
        ) {
            override fun onThrottledWalletChanged() {
                updateAppWidget()
            }

            override fun onCoinsReceived(
                wallet: Wallet, tx: Transaction, prevBalance: Coin,
                newBalance: Coin
            ) {
                val bestChainHeight = blockChain!!.bestChainHeight
                val replaying =
                    bestChainHeight < config.bestChainHeightEver || config.isRestoringBackup
                val now = Date().time
                val blockChainHeadTime = blockChain!!.chainHead.header.time.time
                val insideTxExchangeRateTimeThreshold =
                    now - blockChainHeadTime < TX_EXCHANGE_RATE_TIME_THRESHOLD_MS
                log.info(
                    "onCoinsReceived: {}; rate: {}; replaying: {}; inside: {}, config: {}; will update {}",
                    tx.txId,
                    tx.exchangeRate,
                    replaying,
                    insideTxExchangeRateTimeThreshold,
                    tx.confidence.confidenceType,
                    tx.exchangeRate == null && (!replaying || insideTxExchangeRateTimeThreshold || tx.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING)
                )

                // only set an exchange rate if the tx has no exchange rate and:
                //   1. the blockchain is not being rescanned nor the wallet is being restored OR
                //   2. the transaction is less than three hours old OR
                //   3. the transaction is not yet mined
                if (tx.exchangeRate == null && (!replaying
                            || insideTxExchangeRateTimeThreshold || tx.confidence.confidenceType == TransactionConfidence.ConfidenceType.PENDING)
                ) {
                    try {
                        val exchangeRate = exchangeRatesDao.getRateSync(
                            walletUIConfig.getExchangeCurrencyCodeBlocking()
                        )
                        if (exchangeRate != null) {
                            log.info("Setting exchange rate on received transaction.  Rate:  " + exchangeRate + " tx: " + tx.txId.toString())
                            tx.exchangeRate = ExchangeRate(Coin.COIN, exchangeRate.fiat)
                            application.saveWallet()
                        }
                    } catch (e: Exception) {
                        log.error("Failed to get exchange rate", e)
                    }
                }
                transactionsReceived.incrementAndGet()
                val address = getWalletAddressOfReceived(tx, wallet)
                val amount = tx.getValue(wallet)
                val confidenceType = tx.confidence.confidenceType
                val isRestoringBackup = application.configuration.isRestoringBackup
                handler.post {
                    val isReplayedTx =
                        confidenceType == TransactionConfidence.ConfidenceType.BUILDING && (replaying || isRestoringBackup)
                    if (!isReplayedTx) {
                        if (depositReceivedResponse.matches(tx)) {
                            notificationService.showNotification(
                                "deposit_received",
                                getString(R.string.crowdnode_deposit_received),
                                null,
                                null,
                                Intent(this@BlockchainServiceImpl, StakingActivity::class.java),
                                null
                            )
                        } else if (apiConfirmationHandler != null && apiConfirmationHandler!!.matches(
                                tx
                            )
                        ) {
                            apiConfirmationHandler!!.handle(tx)
                        } else if (passFilters(tx, wallet)) {
                            notifyCoinsReceived(address, amount, tx.exchangeRate)
                        }
                    }
                }
                handleMetadata(tx)
                handleContactPayments(tx)
                updateAppWidget()
            }

            override fun onCoinsSent(
                wallet: Wallet, tx: Transaction, prevBalance: Coin,
                newBalance: Coin
            ) {
                transactionsReceived.incrementAndGet()
                log.info("onCoinsSent: {}", tx.txId)
                if (AssetLockTransaction.isAssetLockTransaction(tx) && tx.purpose == Transaction.Purpose.UNKNOWN) {
                    // Handle credit function transactions (username creation, topup, invites)
                    val authExtension =
                        wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
                    val cftx = authExtension.getAssetLockTransaction(tx)
                    val blockChainHeadTime = blockChain!!.chainHead.header.time.time
                    platformRepo.handleSentAssetLockTransaction(cftx, blockChainHeadTime)

                    // TODO: if we detect a username creation that we haven't processed, should we?
                }
                handleMetadata(tx)
                updateAppWidget()
            }

            private fun passFilters(tx: Transaction, wallet: Wallet): Boolean {
                val amount = tx.getValue(wallet)
                val isReceived = amount.signum() > 0
                if (!isReceived) {
                    return false
                }
                var passFilters = false
                for (filter in crowdnodeFilters) {
                    if (filter.matches(tx)) {
                        passFilters = true
                        break
                    }
                }
                return passFilters
            }
        }
    private val sharedPrefsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _: SharedPreferences?, key: String? ->
            if (key == Configuration.PREFS_KEY_CROWDNODE_PRIMARY_ADDRESS) {
                apiConfirmationHandler = registerCrowdNodeConfirmedAddressFilter()
            }
        }
    private var resetMNListsOnPeerGroupStart = false
    private fun notifyCoinsReceived(
        address: Address?, amount: Coin,
        exchangeRate: ExchangeRate?
    ) {
        if (notificationCount == 1) nm!!.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED)
        notificationCount++
        notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount)
        if (address != null && !notificationAddresses.contains(address)) notificationAddresses.add(
            address
        )
        val btcFormat = config.getFormat()
        val packageFlavor = packageInfoProvider.applicationPackageFlavor()
        var msgSuffix = if (packageFlavor != null) " [$packageFlavor]" else ""
        if (exchangeRate != null) {
            val format = Constants.LOCAL_FORMAT.code(
                0,
                PREFIX_ALMOST_EQUAL_TO + exchangeRate.fiat.getCurrencyCode()
            )
            msgSuffix += " " + format.format(exchangeRate.coinToFiat(notificationAccumulatedAmount))
        }
        val tickerMsg =
            (getString(R.string.notification_coins_received_msg, btcFormat.format(amount))
                    + msgSuffix)
        val msg = getString(
            R.string.notification_coins_received_msg,
            btcFormat.format(notificationAccumulatedAmount)
        ) + msgSuffix
        val text = StringBuilder()
        for (notificationAddress in notificationAddresses) {
            if (text.isNotEmpty()) text.append(", ")
            val addressStr = notificationAddress.toString()
            val label = AddressBookProvider.resolveLabel(applicationContext, addressStr)
            text.append(label ?: addressStr)
        }
        val notification = NotificationCompat.Builder(
            this,
            Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS
        )
        notification.setSmallIcon(R.drawable.ic_dash_d_white)
        notification.setTicker(tickerMsg)
        notification.setContentTitle(msg)
        if (text.isNotEmpty()) {
            notification.setContentText(text)
        }
        notification.setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                createIntent(this),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        notification.setNumber(if (notificationCount == 1) 0 else notificationCount)
        notification.setWhen(System.currentTimeMillis())
        notification.setSound(Uri.parse("android.resource://" + packageName + "/" + R.raw.coins_received))
        nm!!.notify(Constants.NOTIFICATION_ID_COINS_RECEIVED, notification.build())
    }

    private inner class PeerConnectivityListener : PeerConnectedEventListener,
        PeerDisconnectedEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private var peerCount = 0
        private val stopped = AtomicBoolean(false)

        init {
            config.registerOnSharedPreferenceChangeListener(this)
        }

        fun stop() {
            stopped.set(true)
            config.unregisterOnSharedPreferenceChangeListener(this)
            nm!!.cancel(Constants.NOTIFICATION_ID_CONNECTED)
        }

        override fun onPeerConnected(peer: Peer, peerCount: Int) {
            this.peerCount = peerCount
            changed(peerCount)
        }

        override fun onPeerDisconnected(peer: Peer, peerCount: Int) {
            this.peerCount = peerCount
            changed(peerCount)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION == key) changed(peerCount)
        }

        private fun changed(numPeers: Int) {
            if (stopped.get()) return
            val networkStatus = blockchainStateDataProvider.getNetworkStatus()
            if (numPeers > 0 && networkStatus == NetworkStatus.CONNECTING) blockchainStateDataProvider.setNetworkStatus(
                NetworkStatus.CONNECTED
            ) else if (numPeers == 0 && networkStatus == NetworkStatus.DISCONNECTING) blockchainStateDataProvider.setNetworkStatus(
                NetworkStatus.DISCONNECTED
            )
            handler.post {
                val connectivityNotificationEnabled = config.connectivityNotificationEnabled
                if (!connectivityNotificationEnabled || numPeers == 0) {
                    nm!!.cancel(Constants.NOTIFICATION_ID_CONNECTED)
                } else {
                    val notification = Notification.Builder(this@BlockchainServiceImpl)
                    notification.setSmallIcon(
                        R.drawable.stat_sys_peers,
                        if (numPeers > 4) 4 else numPeers
                    )
                    notification.setContentTitle(getString(R.string.app_name))
                    notification.setContentText(
                        getString(
                            R.string.notification_peers_connected_msg,
                            numPeers
                        )
                    )
                    notification.setContentIntent(
                        PendingIntent.getActivity(
                            this@BlockchainServiceImpl, 0,
                            createIntent(this@BlockchainServiceImpl), PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    notification.setWhen(System.currentTimeMillis())
                    notification.setOngoing(true)
                    nm!!.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.build())
                }

                // send broadcast
                broadcastPeerState(numPeers)
            }
        }
    }

    private abstract inner class MyDownloadProgressTracker
        : DownloadProgressTracker(Constants.SYNC_FLAGS.contains(MasternodeSync.SYNC_FLAGS.SYNC_BLOCKS_AFTER_PREPROCESSING)), OnPreBlockProgressListener

    private val blockchainDownloadListener: MyDownloadProgressTracker =
        object : MyDownloadProgressTracker() {
            private val lastMessageTime = AtomicLong(0)
            private var throttleDelay: Long = -1
            override fun onBlocksDownloaded(
                peer: Peer, block: Block, filteredBlock: FilteredBlock?,
                blocksLeft: Int
            ) {
                super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft)
                postOrPostDelayed()
            }

            override fun onHeadersDownloaded(
                peer: Peer, block: Block,
                blocksLeft: Int
            ) {
                super.onHeadersDownloaded(peer, block, blocksLeft)
                postOrPostDelayed()
            }

            private val runnable = Runnable {
                lastMessageTime.set(System.currentTimeMillis())
                log.debug("Runnable % = $syncPercentage")
                config.maybeIncrementBestChainHeightEver(blockChain!!.chainHead.height)
                config.maybeIncrementBestHeaderHeightEver(headerChain!!.chainHead.height)
                if (config.isRestoringBackup) {
                    val timeAgo =
                        System.currentTimeMillis() - blockChain!!.chainHead.header.timeSeconds * 1000
                    //if the app was restoring a backup from a file or seed and block chain is nearly synced
                    //then turn off the restoring indicator
                    if (timeAgo < DateUtils.DAY_IN_MILLIS) {
                        config.isRestoringBackup = false
                    }
                }
                // this method is always called after progress or doneDownload
                updateBlockchainState()
            }

            /*
            This method is called by super.onBlocksDownloaded when the percentage
            of the chain downloaded is 0.0, 1.0, 2.0, 3.0 .. 99.0% (whole numbers)

            The pct value is relative to the blocks that need to be downloaded to sync,
            rather than the relative to the entire blockchain.
         */
            override fun progress(pct: Double, blocksLeft: Int, date: Date) {
                super.progress(pct, blocksLeft, date)
                syncPercentage = if (pct > 0.0) pct.toInt() else 0
                log.info("progress {}", syncPercentage)
                if (syncPercentage > 100) {
                    syncPercentage = 100
                }
            }

            /** This method is called by super.onBlocksDownloaded when the percentage
                of the chain downloaded is 100.0% (completely done)
            */
            override fun doneDownload() {
                super.doneDownload()
                log.info("DoneDownload {}", syncPercentage)
                // if the chain is already synced from a previous session, then syncPercentage = 0
                // set to 100% so that observers will see that sync is completed
                syncPercentage = 100
                updateBlockchainState()
                if (Constants.SUPPORTS_PLATFORM) {
                    serviceScope.launch {
                        platformRepo.updateFrequentContacts()
                    }
                }
            }

            override fun onMasterNodeListDiffDownloaded(
                stage: MasternodeListDownloadedListener.Stage,
                mnlistdiff: SimplifiedMasternodeListDiff?
            ) {
                log.info("masternodeListDiffDownloaded:$stage")
                if (peerGroup != null && peerGroup!!.syncStage == PeerGroup.SyncStage.MNLIST) {
                    super.onMasterNodeListDiffDownloaded(stage, mnlistdiff)
                    startPreBlockPercent = syncPercentage
                    mnListDiffsReceived.incrementAndGet()
                    postOrPostDelayed()
                }
            }

            private fun postOrPostDelayed() {
                delayHandler.removeCallbacksAndMessages(null)
                if (throttleDelay == -1L) {
                    throttleDelay =
                        if (application.isLowRamDevice()) BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS else BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS / 4
                }
                val now = System.currentTimeMillis()
                if (now - lastMessageTime.get() > throttleDelay) {
                    delayHandler.post(runnable)
                } else {
                    delayHandler.postDelayed(runnable, throttleDelay)
                }
            }

            var totalPreblockStages = PreBlockStage.UpdateTotal.value
            var startPreBlockPercent = 0
            var lastPreBlockStage = PreBlockStage.None
            override fun onPreBlockProgressUpdated(stage: PreBlockStage) {
                if (stage == PreBlockStage.Starting && lastPreBlockStage == PreBlockStage.None) {
                    startPreBlockPercent = syncPercentage
                }
                if (preBlocksWeight > 0.99) {
                    startPreBlockPercent = 0
                }
                if (stage == PreBlockStage.StartRecovery && lastPreBlockStage == PreBlockStage.None) {
                    startPreBlockPercent = syncPercentage
                    if (preBlocksWeight <= 0.10) setPreBlocksWeight(0.20)
                }
                var increment = preBlocksWeight * stage.value * 100.0 / PreBlockStage.Complete.value
                if (increment > preBlocksWeight * 100) increment = preBlocksWeight * 100
                log.debug("PreBlockDownload: " + increment + "%..." + preBlocksWeight + " " + stage.name + " " + peerGroup!!.syncStage.name)
                if (peerGroup != null && peerGroup!!.syncStage == PeerGroup.SyncStage.PREBLOCKS) {
                    syncPercentage = (startPreBlockPercent + increment).toInt()
                    log.info("PreBlockDownload: " + syncPercentage + "%..." + stage.name)
                    postOrPostDelayed()
                }
                lastPreBlockStage = stage
            }
        }
    private var connectivityReceiverRegistered = false
    private val connectivityReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serviceScope.launch {
                val action = intent.action
                if (ConnectivityManager.CONNECTIVITY_ACTION == action) {
                    val networkInfo = connectivityManager.activeNetworkInfo
                    val hasConnectivity = networkInfo != null && networkInfo.isConnected
                    if (log.isInfoEnabled) {
                        val s = StringBuilder("active network is ")
                            .append(if (hasConnectivity) "up" else "down")
                        if (networkInfo != null) {
                            s.append(", type: ").append(networkInfo.typeName)
                            s.append(", state: ").append(networkInfo.state).append('/')
                                .append(networkInfo.detailedState)
                            val extraInfo = networkInfo.extraInfo
                            if (extraInfo != null) s.append(", extraInfo: ").append(extraInfo)
                            val reason = networkInfo.reason
                            if (reason != null) s.append(", reason: ").append(reason)
                        }
                        log.info(s.toString())
                    }
                    if (hasConnectivity) {
                        impediments.remove(Impediment.NETWORK)
                    } else {
                        impediments.add(Impediment.NETWORK)
                    }
                    updateBlockchainStateImpediments()
                    check()
                } else if (Intent.ACTION_DEVICE_STORAGE_LOW == action) {
                    log.info("device storage low")
                    impediments.add(Impediment.STORAGE)
                    updateBlockchainStateImpediments()
                    check()
                } else if (Intent.ACTION_DEVICE_STORAGE_OK == action) {
                    log.info("device storage ok")
                    impediments.remove(Impediment.STORAGE)
                    updateBlockchainStateImpediments()
                    check()
                }
            }
        }

        private fun check() {
            serviceScope.launch {
                // make sure that onCreate is finished
                onCreateCompleted.await()
                log.info("acquiring check() mutex")
                checkMutex.lock()
                try {
                    checkService()
                } finally {
                    checkMutex.unlock()
                    log.info("releasing check() mutex")
                }
            }
        }

        @SuppressLint("Wakelock")
        private fun checkService() {
            log.info("check()")
            val wallet = application.wallet
            if (impediments.isEmpty() && peerGroup == null) {
                log.debug("acquiring wakelock")
                wakeLock!!.acquire()

                // consistency check
                val walletLastBlockSeenHeight = wallet!!.lastBlockSeenHeight
                val bestChainHeight = blockChain!!.bestChainHeight
                if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                    val message =
                        ("wallet/blockchain out of sync: " + walletLastBlockSeenHeight + "/"
                                + bestChainHeight)
                    log.error(message)
                    CrashReporter.saveBackgroundTrace(
                        RuntimeException(message),
                        packageInfoProvider.packageInfo
                    )
                }
                propagateContext()
                dashSystemService.system.initDashSync(getDir("masternode", MODE_PRIVATE).absolutePath)
                log.info("starting peergroup")
                peerGroup = PeerGroup(Constants.NETWORK_PARAMETERS, blockChain, headerChain)
                if (Constants.SUPPORTS_PLATFORM) {
                    platformRepo.platform.setMasternodeListManager(dashSystemService.system.masternodeListManager)
                    platformSyncService.resume()
                }
                if (resetMNListsOnPeerGroupStart) {
                    resetMNListsOnPeerGroupStart = false
                    dashSystemService.system.masternodeListManager.setBootstrap(
                        mnlistinfoBootStrapStream,
                        qrinfoBootStrapStream,
                        SimplifiedMasternodeListManager.SMLE_VERSION_FORMAT_VERSION
                    )
                    resetMNLists(true)
                }
                peerGroup!!.setDownloadTxDependencies(0) // recursive implementation causes StackOverflowError
                peerGroup!!.addWallet(wallet)
                peerGroup!!.setUserAgent(Constants.USER_AGENT, packageInfoProvider.versionName)
                peerGroup!!.addConnectedEventListener(peerConnectivityListener)
                peerGroup!!.addDisconnectedEventListener(peerConnectivityListener)
                val maxConnectedPeers = application.maxConnectedPeers()
                val trustedPeerHost = config.trustedPeerHost
                val hasTrustedPeer = trustedPeerHost != null
                val connectTrustedPeerOnly = hasTrustedPeer && config.trustedPeerOnly
                peerGroup!!.maxConnections = if (connectTrustedPeerOnly) 1 else maxConnectedPeers
                peerGroup!!.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS)
                peerGroup!!.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS.toLong())
                peerGroup!!.addPeerDiscovery(object : PeerDiscovery {
                    //Keep Original code here for now
                    //private final PeerDiscovery normalPeerDiscovery = MultiplexingDiscovery
                    //        .forServices(Constants.NETWORK_PARAMETERS, 0);
                    private val normalPeerDiscovery: PeerDiscovery =
                        MultiplexingDiscovery(Constants.NETWORK_PARAMETERS, peerDiscoveryList)

                    @Throws(PeerDiscoveryException::class)
                    override fun getPeers(
                        services: Long, timeoutValue: Long,
                        timeoutUnit: TimeUnit
                    ): Array<InetSocketAddress> {
                        val peers: MutableList<InetSocketAddress> = LinkedList()
                        var needsTrimPeersWorkaround = false
                        if (hasTrustedPeer) {
                            log.info(
                                "trusted peer '" + trustedPeerHost + "'" + if (connectTrustedPeerOnly) " only" else ""
                            )
                            val addr = InetSocketAddress(
                                trustedPeerHost,
                                Constants.NETWORK_PARAMETERS.port
                            )
                            if (addr.address != null) {
                                peers.add(addr)
                                needsTrimPeersWorkaround = true
                            }
                        }
                        if (!connectTrustedPeerOnly) {
                            // First use the masternode list that is included
                            try {
                                val mnlist = dashSystemService.system.masternodeListManager.listAtChainTip
                                val discovery = MasternodePeerDiscovery(mnlist)
                                peers.addAll(
                                    listOf(
                                        *discovery.getPeers(
                                            services,
                                            timeoutValue,
                                            timeoutUnit
                                        )
                                    )
                                )
                            } catch (x: PeerDiscoveryException) {
                                //swallow and continue with another method of connection
                                log.info("DMN List peer discovery failed: " + x.message)
                            }

                            // default masternode list
                            if (peers.size < MINIMUM_PEER_COUNT) {
                                val defaultMNList =
                                    Constants.NETWORK_PARAMETERS.defaultMasternodeList
                                if (defaultMNList != null && defaultMNList.isNotEmpty()) {
                                    log.info("DMN peer discovery returned less than 16 nodes.  Adding default DMN peers to the list to increase connections")
                                    val discovery = MasternodePeerDiscovery(
                                        defaultMNList,
                                        Constants.NETWORK_PARAMETERS.port
                                    )
                                    peers.addAll(
                                        listOf(
                                            *discovery.getPeers(
                                                services,
                                                timeoutValue,
                                                timeoutUnit
                                            )
                                        )
                                    )

                                    // use EvoNodes if the network is small
                                    if (peers.size < MINIMUM_PEER_COUNT) {
                                        val defaultEvoNodeList =
                                            Constants.NETWORK_PARAMETERS.defaultHPMasternodeList
                                        val discoveryEvo = MasternodePeerDiscovery(
                                            defaultEvoNodeList,
                                            Constants.NETWORK_PARAMETERS.port
                                        )
                                        peers.addAll(
                                            listOf(
                                                *discoveryEvo.getPeers(
                                                    services,
                                                    timeoutValue,
                                                    timeoutUnit
                                                )
                                            )
                                        )
                                    }
                                } else {
                                    log.info("DNS peer discovery returned less than 16 nodes.  Unable to add seed peers (it is not specified for this network).")
                                }
                            }

                            // seed nodes
                            if (peers.size < MINIMUM_PEER_COUNT) {
                                if (Constants.NETWORK_PARAMETERS.addrSeeds != null) {
                                    log.info("Static DMN peer discovery returned less than 16 nodes.  Adding seed peers to the list to increase connections")
                                    peers.addAll(
                                        listOf(
                                            *seedPeerDiscovery.getPeers(
                                                services,
                                                timeoutValue,
                                                timeoutUnit
                                            )
                                        )
                                    )
                                } else {
                                    log.info("DNS peer discovery returned less than 16 nodes.  Unable to add seed peers (it is not specified for this network).")
                                }
                            }
                            if (peers.size < MINIMUM_PEER_COUNT) {
                                log.info("Masternode peer discovery returned less than 16 nodes.  Adding DMN peers to the list to increase connections")
                                try {
                                    peers.addAll(
                                        listOf(
                                            *normalPeerDiscovery.getPeers(
                                                services,
                                                timeoutValue,
                                                timeoutUnit
                                            )
                                        )
                                    )
                                } catch (x: PeerDiscoveryException) {
                                    //swallow and continue with another method of connection, if one exists.
                                    log.info("DNS peer discovery failed: " + x.message)
                                    if (x.cause != null) log.info("cause:  " + x.cause!!.message)
                                }
                            }
                        }

                        // workaround because PeerGroup will shuffle peers
                        if (needsTrimPeersWorkaround) while (peers.size >= maxConnectedPeers) peers.removeAt(
                            peers.size - 1
                        )
                        return peers.toTypedArray<InetSocketAddress>()
                    }

                    override fun shutdown() {
                        normalPeerDiscovery.shutdown()
                    }
                })
                peerGroup!!.addPreBlocksDownloadListener(executor, preBlocksDownloadListener)
                // Use our custom risk analysis that allows v2 tx with absolute LockTime
                riskAnalyzer = AllowLockTimeRiskAnalysis.Analyzer(
                    peerGroup!!
                )
                wallet.riskAnalyzer = riskAnalyzer

                // start peergroup
                blockchainStateDataProvider.setNetworkStatus(NetworkStatus.CONNECTING)
                peerGroup!!.startAsync()
                peerGroup!!.startBlockChainDownload(blockchainDownloadListener)
                platformSyncService.addPreBlockProgressListener(blockchainDownloadListener)
            } else if (impediments.isNotEmpty() && peerGroup != null) {
                blockchainStateDataProvider.setNetworkStatus(NetworkStatus.NOT_AVAILABLE)
                dashSystemService.system.close()
                log.info("stopping peergroup")
                peerGroup!!.removeDisconnectedEventListener(peerConnectivityListener)
                peerGroup!!.removeConnectedEventListener(peerConnectivityListener)
                peerGroup!!.removePreBlocksDownloadedListener(preBlocksDownloadListener)
                peerGroup!!.removeWallet(wallet)
                platformSyncService.removePreBlockProgressListener(blockchainDownloadListener)
                peerGroup!!.stopAsync()
                // use the offline risk analyzer
                wallet!!.riskAnalyzer =
                    OfflineAnalyzer(config.bestHeightEver, System.currentTimeMillis() / 1000)
                riskAnalyzer!!.shutdown()
                peerGroup = null
                log.debug("releasing wakelock")
                wakeLock!!.release()
            }
        }
    }

    private class ActivityHistoryEntry(
        val numTransactionsReceived: Int, val numBlocksDownloaded: Int,
        val numHeadersDownloaded: Int, val numMnListDiffsDownloaded: Int
    ) {
        override fun toString(): String {
            return "$numTransactionsReceived/$numBlocksDownloaded/$numHeadersDownloaded/$numMnListDiffsDownloaded"
        }
    }

    private var tickRecieverRegistered = false
    private val tickReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        private var lastChainHeight = 0
        private var lastHeaderHeight = 0
        private val activityHistory = arrayListOf<ActivityHistoryEntry> ()
        override fun onReceive(context: Context, intent: Intent) {
            val chainHeight = blockChain!!.bestChainHeight
            val headerHeight = headerChain!!.bestChainHeight
            if (lastChainHeight > 0 || lastHeaderHeight > 0) {
                val numBlocksDownloaded = chainHeight - lastChainHeight
                val numTransactionsReceived = transactionsReceived.getAndSet(0)
                // instead of counting headers, count header messages which contain up to 2000 headers
                val numHeadersDownloaded = headerHeight - lastHeaderHeight
                val numMnListDiffsDownloaded = mnListDiffsReceived.getAndSet(0)

                // push history
                activityHistory.add(
                    0,
                    ActivityHistoryEntry(
                        numTransactionsReceived,
                        numBlocksDownloaded,
                        numHeadersDownloaded,
                        numMnListDiffsDownloaded
                    )
                )

                // trim
                while (activityHistory.size > MAX_HISTORY_SIZE) {
                    activityHistory.removeAt(
                        activityHistory.size - 1
                    )
                }

                // print
                val builder = StringBuilder()
                for (entry in activityHistory) {
                    if (builder.isNotEmpty()) {
                        builder.append(", ")
                    }
                    builder.append(entry)
                }
                log.info(
                    "History of transactions/blocks/headers/mnlistdiff: " +
                            (if (mixingStatus == MixingStatus.MIXING) "[mixing] " else "") + builder
                )

                // determine if block and transaction activity is idling
                var isIdle = false
                if (activityHistory.size >= MIN_COLLECT_HISTORY) {
                    isIdle = true
                    for (i in activityHistory.indices) {
                        val entry = activityHistory[i]
                        val blocksActive =
                            entry.numBlocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN
                        val transactionsActive = (entry.numTransactionsReceived > 0
                                && i <= IDLE_TRANSACTION_TIMEOUT_MIN)
                        val headersActive =
                            entry.numHeadersDownloaded > 0 && i <= IDLE_HEADER_TIMEOUT_MIN
                        val mnListDiffsActive =
                            entry.numMnListDiffsDownloaded > 0 && i <= IDLE_MNLIST_TIMEOUT_MIN
                        if (blocksActive || transactionsActive || headersActive || mnListDiffsActive) {
                            isIdle = false
                            break
                        }
                    }
                }

                // if idling, shutdown service
                if (isIdle && mixingStatus != MixingStatus.MIXING) {
                    log.info("idling detected, stopping service")
                    stopSelf()
                }
            }
            lastChainHeight = chainHeight
            lastHeaderHeight = headerHeight
        }
    }

    inner class LocalBinder : Binder() {
        val service: BlockchainServiceImpl
            get() = this@BlockchainServiceImpl
    }

    private val mBinder: IBinder = LocalBinder()
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        log.debug(".onBind()")
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        log.debug(".onUnbind()")
        return super.onUnbind(intent)
    }

    private fun propagateContext() {
        if (application.wallet?.context != Constants.CONTEXT) {
            log.warn("wallet context does not equal Constants.CONTEXT")
        }
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
    }

    override fun onCreate() {
        serviceCreatedAt = System.currentTimeMillis()
        log.info(".onCreate()")
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val lockName = "$packageName blockchain sync"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundAndCatch(createNetworkSyncNotification())
        }
        serviceScope.launch {
            try {
                log.info("onCreate() serviceScope waiting for cleanup {}", cleanupDeferred?.isActive)
                cleanupDeferred?.await()
                propagateContext()
                val wallet = application.wallet
                if (wallet == null) {
                    log.error("onCreate: wallet is null after cleanup, service cannot continue")
                    return@launch
                }
                peerConnectivityListener = PeerConnectivityListener()
                broadcastPeerState(0)
                blockChainFile =
                    File(getDir("blockstore", MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME)
                val blockChainFileExists = blockChainFile!!.exists()
                headerChainFile = File(getDir("blockstore", MODE_PRIVATE), Constants.Files.HEADERS_FILENAME)
                mnlistinfoBootStrapStream = loadStream(Constants.Files.MNLIST_BOOTSTRAP_FILENAME)
                qrinfoBootStrapStream = loadStream(Constants.Files.QRINFO_BOOTSTRAP_FILENAME)
                if (!blockChainFileExists) {
                    log.info("blockchain does not exist, resetting wallet")
                    propagateContext()
                    wallet.reset()
                    resetMNLists(false)
                    resetMNListsOnPeerGroupStart = true
                }
                try {
                    blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile)
                    blockStore?.chainHead // detect corruptions as early as possible
                    headerStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, headerChainFile)
                    headerStore?.chainHead // detect corruptions as early as possible
                    withContext(Dispatchers.Main) { verifyBlockStores() }
                    val earliestKeyCreationTime = wallet.earliestKeyCreationTime
                    if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                        try {
                            val watch = Stopwatch.createStarted()
                            var checkpointsInputStream = assets.open(Constants.Files.CHECKPOINTS_FILENAME)
                            CheckpointManager.checkpoint(
                                Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore,
                                earliestKeyCreationTime
                            )
                            //the headerStore should be set to the most recent checkpoint
                            checkpointsInputStream = assets.open(Constants.Files.CHECKPOINTS_FILENAME)
                            CheckpointManager.checkpoint(
                                Constants.NETWORK_PARAMETERS, checkpointsInputStream, headerStore,
                                System.currentTimeMillis() / 1000
                            )
                            watch.stop()
                            log.info(
                                "checkpoints loaded from '{}', took {}",
                                Constants.Files.CHECKPOINTS_FILENAME,
                                watch
                            )
                        } catch (x: IOException) {
                            log.error("problem reading checkpoints, continuing without", x)
                        }
                    }
                } catch (x: BlockStoreException) {
                    blockChainFile!!.delete()
                    headerChainFile!!.delete()
                    resetMNLists(false)
                    val msg = "blockstore cannot be created"
                    log.error(msg, x)
                    throw Error(msg, x)
                }
                try {
                    blockChain = BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore)
                    headerChain = BlockChain(Constants.NETWORK_PARAMETERS, headerStore)
                    blockchainStateDataProvider.setBlockChain(blockChain)
                } catch (x: BlockStoreException) {
                    throw Error("blockchain cannot be created", x)
                }
                // register receivers on the main thread
                withContext(Dispatchers.Main) {
                    val intentFilter = IntentFilter()
                    intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                    intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                    intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK)
                    registerReceiver(connectivityReceiver, intentFilter) // implicitly start PeerGroup
                    connectivityReceiverRegistered = true
                    log.info("receiver register: connectivityReceiver, {}", connectivityReceiver)
                }
                wallet.addCoinsReceivedEventListener(
                    Threading.SAME_THREAD,
                    walletEventListener
                )
                wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener)
                wallet.addChangeEventListener(Threading.SAME_THREAD, walletEventListener)
                config.registerOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
                withContext(Dispatchers.Main) {
                    registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
                    tickRecieverRegistered = true
                    log.info("receiver register: tickReceiver, {}", tickReceiver)
                }
                peerDiscoveryList.add(dnsDiscovery)
                updateAppWidget()
                blockchainStateDao.observeState().observe(this@BlockchainServiceImpl) { blockchainState ->
                    handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress)
                }
                apiConfirmationHandler = registerCrowdNodeConfirmedAddressFilter()
                coinJoinService.observeMixingState().observe(this@BlockchainServiceImpl) { mixingStatus ->
                    handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress)
                }
                coinJoinService.observeMixingProgress().observe(this@BlockchainServiceImpl) { mixingProgress ->
                    handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress)
                }

                // we need the total wallet balance for the CoinJoin notification
                application.observeTotalBalance().observe(this@BlockchainServiceImpl) {
                    balance = it
                    handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress)
                }

                // we need the mixed balance for the CoinJoin notification
                application.observeMixedBalance().observe(this@BlockchainServiceImpl) {
                    mixedBalance = it
                    handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress)
                }

                onCreateCompleted.complete(Unit) // Signal completion of onCreate
                log.info(".onCreate() finished")
            } finally {
                log.error(".onCreate() failed")
                if (onCreateCompleted.isActive) {
                    onCreateCompleted.complete(Unit)
                }
            }
        }
    }

    private fun createCoinJoinNotification(): Notification {
        val notificationIntent = createIntent(this)
        val decimalFormat = DecimalFormat("0.000")
        val statusStringId = when (mixingStatus) {
            MixingStatus.NOT_STARTED -> R.string.coinjoin_not_started
            MixingStatus.MIXING -> R.string.coinjoin_mixing
            MixingStatus.FINISHING -> R.string.coinjoin_mixing_finishing
            MixingStatus.PAUSED -> R.string.coinjoin_paused
            MixingStatus.FINISHED -> R.string.coinjoin_progress_finished
            else -> R.string.error
        }
        val message = getString(
            R.string.coinjoin_progress,
            getString(statusStringId),
            mixingProgress,
            decimalFormat.format(mixedBalance.toBigDecimal()),
            decimalFormat.format(balance.toBigDecimal())
        )
        return notificationService.buildNotification(
            message,
            getString(R.string.app_name),
            null,
            notificationIntent,
            Constants.NOTIFICATION_CHANNEL_ID_ONGOING
        )
    }

    private fun resetMNLists(requestFreshList: Boolean) {
        try {
            val manager = dashSystemService.system.masternodeListManager
            manager?.resetMNList(true, requestFreshList)
        } catch (x: RuntimeException) {
            // swallow this exception.  It is thrown when there is not a bootstrap file
            // there is not a bootstrap mnlist file for testnet
            log.info("error resetting masternode list with bootstrap files", x)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.info(".onStartCommand($intent)")
        super.onStartCommand(intent, flags, startId)
        serviceScope.launch {
            log.info("onStartCommand waiting for onCreate to complete...")
            onCreateCompleted.await() // wait until onCreate is finished
            log.info("onCreate completed, processing onStartCommand")
            if (intent != null) {
                propagateContext()
                //Restart service as a Foreground Service if it's synchronizing the blockchain
                val extras = intent.extras
                if (extras != null && extras.containsKey(START_AS_FOREGROUND_EXTRA)) {
                    startForegroundAndCatch(createNetworkSyncNotification())
                }
                log.info(
                    "service start command: $intent" + if (intent.hasExtra(Intent.EXTRA_ALARM_COUNT)) " (alarm count: " + intent.getIntExtra(
                        Intent.EXTRA_ALARM_COUNT, 0
                    ) + ")" else ""
                )
                val action = intent.action
                if (BlockchainService.ACTION_CANCEL_COINS_RECEIVED == action) {
                    notificationCount = 0
                    notificationAccumulatedAmount = Coin.ZERO
                    notificationAddresses.clear()
                    nm!!.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED)
                } else if (BlockchainService.ACTION_RESET_BLOCKCHAIN == action) {
                    log.info("will remove blockchain on service shutdown")
                    resetBlockchainOnShutdown = true
                    stopSelf()
                } else if (BlockchainService.ACTION_WIPE_WALLET == action) {
                    log.info("will remove blockchain and delete walletFile on service shutdown")
                    deleteWalletFileOnShutdown = true
                    stopSelf()
                } else if (BlockchainService.ACTION_BROADCAST_TRANSACTION == action) {
                    val hash = Sha256Hash
                        .wrap(intent.getByteArrayExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH))
                    log.info("broadcast transaction requested for hash: {}", hash)
                    val wallet = application.wallet
                    if (wallet != null) {
                        val tx = wallet.getTransaction(hash)
                        if (tx != null) {
                            log.info("found transaction {} in wallet", tx.txId)
                            if (peerGroup != null) {
                                val count = peerGroup!!.numConnectedPeers()
                                log.info("broadcasting transaction {} with {} connected peers", tx.txId, count)
                                var minimum = peerGroup!!.minBroadcastConnections
                                // if the number of peers is <= 3, then only require that number of peers to send
                                // if the number of peers is 0, then require 3 peers (default min connections)
                                if (count in 1..3) minimum = count
                                peerGroup!!.broadcastTransaction(tx, minimum, true)
                                log.info("transaction {} broadcast initiated", tx.txId)
                            } else {
                                log.warn("peergroup not available, not broadcasting transaction {}", tx.txId)
                                tx.confidence.setPeerInfo(0, 1)
                            }
                        } else {
                            log.error("transaction {} not found in wallet", hash)
                        }
                    } else {
                        log.error("wallet is null, cannot broadcast transaction")
                    }
                } else if (BlockchainService.ACTION_RESET_BLOOMFILTERS == action) {
                    if (peerGroup != null) {
                        log.info("recalculating bloom filters")
                        peerGroup!!.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.FORCE_SEND_FOR_REFRESH)
                    } else {
                        log.info("peergroup not available, not recalculating bloom filers")
                    }
                }
            } else {
                log.warn("service restart, although it was started as non-sticky")
            }
            log.info(".onStartCommand($intent) finished")
        }
        return START_NOT_STICKY
    }

    private fun startForeground(notification: Notification) {
        //Shows ongoing notification promoting service to foreground service and
        //preventing it from being killed in Android 26 or later
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification)
        }
        foregroundService = ForegroundService.BLOCKCHAIN_SYNC
    }

    private fun startForegroundAndCatch(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startForeground(notification)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                log.info("failed to start in foreground, try again", e)
                // On Android 15+, we'll retry later when the app is in foreground
                // For now, continue running as a regular service
                scheduleRetryForegroundService(notification)
            }
        } else {
            startForeground(notification)
        }
    }

    private fun scheduleRetryForegroundService(notification: Notification) {
        pendingForegroundNotification = notification
        // Schedule a retry after a few seconds to see if the app comes to foregrxound
        handler.postDelayed({
            if (pendingForegroundNotification != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    startForeground(pendingForegroundNotification!!)
                    pendingForegroundNotification = null
                    log.info("Successfully started foreground service on retry")
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    log.info("Foreground service start still not allowed, will continue as background service")
                    pendingForegroundNotification = null
                }
            }
        }, 5000) // Retry after 5 seconds
    }

    override fun onDestroy() {
        log.info(".onDestroy()")
        super.onDestroy()
        // unregister receivers on the main thread, if they were registered
        // in some cases, onDestroy is called soon after onCreate and before its coroutine finishes
        if (tickRecieverRegistered) {
            unregisterReceiver(tickReceiver)
            tickRecieverRegistered = false
        }
        if (connectivityReceiverRegistered) {
            unregisterReceiver(connectivityReceiver)
            connectivityReceiverRegistered = false
        }
        serviceScope.launch {
            try {
                log.info("The onCreateCompleted is active: {}", onCreateCompleted.isActive)
                onCreateCompleted.await() // wait until onCreate is finished
                log.info("The check() mutex is locked: {}", checkMutex.isLocked)
                cleanupDeferred = CompletableDeferred()
                checkMutex.lock()
                WalletApplication.scheduleStartBlockchainService(this@BlockchainServiceImpl) //disconnect feature
                val wallet = application.wallet
                if (wallet != null) {
                    wallet.removeChangeEventListener(walletEventListener)
                    wallet.removeCoinsSentEventListener(walletEventListener)
                    wallet.removeCoinsReceivedEventListener(walletEventListener)
                }
                config.unregisterOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
                platformSyncService.shutdown()
                if (peerGroup != null) {
                    propagateContext()
                    dashSystemService.system.close()
                    peerGroup!!.removeDisconnectedEventListener(peerConnectivityListener)
                    peerGroup!!.removeConnectedEventListener(peerConnectivityListener)
                    peerGroup!!.removeWallet(application.wallet)
                    platformSyncService.removePreBlockProgressListener(blockchainDownloadListener)
                    blockchainStateDataProvider.setNetworkStatus(NetworkStatus.DISCONNECTING)
                    peerGroup!!.stop()
                    blockchainStateDataProvider.setNetworkStatus(NetworkStatus.STOPPED)
                    if (wallet != null) {
                        wallet.riskAnalyzer = defaultRiskAnalyzer
                    }
                    riskAnalyzer!!.shutdown()
                    log.info("peergroup stopped")
                }
                peerConnectivityListener!!.stop()
                delayHandler.removeCallbacksAndMessages(null)
                try {
                    blockStore!!.close()
                    headerStore!!.close()
                    blockchainStateDataProvider.setBlockChain(null)
                } catch (x: BlockStoreException) {
                    throw RuntimeException(x)
                }
                if (!deleteWalletFileOnShutdown) {
                    propagateContext()
                    application.saveWallet()
                }
                if (wakeLock!!.isHeld) {
                    log.debug("wakelock still held, releasing")
                    wakeLock!!.release()
                }
                if (resetBlockchainOnShutdown || deleteWalletFileOnShutdown) {
                    log.info("removing blockchain")
                    blockChainFile!!.delete()
                    headerChainFile!!.delete()
                    resetMNLists(false)
                    if (deleteWalletFileOnShutdown) {
                        log.info("removing wallet file and app data")
                        coinJoinService.shutdown()
                        application.finalizeWipe()
                    }
                    //Clear the blockchain identity
                    application.clearDatabases(false)
                }
                closeStream(mnlistinfoBootStrapStream)
                closeStream(qrinfoBootStrapStream)
            } finally {
                log.info("serviceJob cancelled after " + (System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60 + " minutes")
                serviceJob.cancel()
                checkMutex.unlock()
                cleanupDeferred?.complete(Unit)
            }
        }
        log.info("service was up for " + (System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60 + " minutes")
    }

    override fun onTrimMemory(level: Int) {
        log.info("onTrimMemory({}) called", level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            log.warn("low memory detected, stopping service")
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int, fgsType: Int) {
        log.warn("Foreground service timeout reached for startId: $startId, fgsType: $fgsType")
        log.info("DataSync foreground service 6-hour limit exceeded, stopping service")
        
        // Show notification about the timeout
        showTimeoutNotification()
        
        // Android 15 requires us to call stopSelf() within a few seconds
        // when onTimeout is called for dataSync services
        try {
            stopSelf()
        } catch (e: Exception) {
            log.error("Error stopping service on timeout", e)
        }
        
        super.onTimeout(startId, fgsType)
    }

    private fun showTimeoutNotification() {
        val mainActivityIntent = MainActivity.createIntent(this).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            coinJoinService.isMixing() -> getString(R.string.coinjoin_paused)
            blockchainState?.replaying == true -> getString(R.string.notification_sync_paused)
            else -> getString(R.string.notification_background_processes_paused)
        }
        val text = getString(R.string.notification_background_processes_paused_text)
        
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID_ONGOING)
            .setSmallIcon(R.drawable.ic_dash_d_white)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        nm?.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification)
    }

    private fun createNetworkSyncNotification(blockchainState: BlockchainState? = null): Notification {
        val notificationIntent = createIntent(this)
        val message = if (blockchainState != null) BlockchainStateUtils.getSyncStateString(
            blockchainState,
            this
        ) else getString(
            R.string.blockchain_state_progress_downloading, "0"
        )
        return notificationService.buildNotification(
            message ?: "",
            getString(R.string.app_name),
            null,
            notificationIntent,
            Constants.NOTIFICATION_CHANNEL_ID_ONGOING
        )
    }

    private fun updateBlockchainStateImpediments() {
        blockchainStateDataProvider.updateImpediments(impediments)
    }

    private fun updateBlockchainState() {
        blockchainStateDataProvider.updateBlockchainState(
            blockChain!!, impediments, percentageSync(),
            if (peerGroup != null) peerGroup!!.syncStage else null
        )
    }

    override fun getConnectedPeers(): List<Peer>? {
        return if (peerGroup != null) peerGroup!!.connectedPeers else null
    }

    override fun getRecentBlocks(maxBlocks: Int): List<StoredBlock> {
        val blocks: MutableList<StoredBlock> = ArrayList(maxBlocks)
        try {
            var block = blockChain?.chainHead
            while (block != null) {
                blocks.add(block)
                if (blocks.size >= maxBlocks) break
                block = block.getPrev(blockStore)
            }
        } catch (x: BlockStoreException) {
            // swallow
        }
        return blocks
    }

    private fun broadcastPeerState(numPeers: Int) {
        val broadcast = Intent(BlockchainService.ACTION_PEER_STATE)
        broadcast.setPackage(packageName)
        broadcast.putExtra(BlockchainService.ACTION_PEER_STATE_NUM_PEERS, numPeers)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
    }

    private fun handleBlockchainStateNotification(
        blockchainState: BlockchainState?,
        mixingStatus: MixingStatus,
        mixingProgress: Double
    ) {
        // send this out for the Network Monitor, other activities observe the database
        val broadcast = Intent(BlockchainService.ACTION_BLOCKCHAIN_STATE)
        broadcast.setPackage(packageName)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
        // log.info("handle blockchain state notification: {}, {}", foregroundService, mixingStatus);
        this.mixingProgress = mixingProgress
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && blockchainState != null && blockchainState.bestChainDate != null) {
            //Handle Ongoing notification state
            val syncing =
                blockchainState.bestChainDate!!.time < Utils.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS //1 hour
            if (!syncing && blockchainState.bestChainHeight == config.bestChainHeightEver && mixingStatus != MixingStatus.MIXING && mixingStatus != MixingStatus.FINISHING) {
                //Remove ongoing notification if blockchain sync finished
                stopForeground(true)
                foregroundService = ForegroundService.NONE
                nm!!.cancel(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC)
            } else if (blockchainState.replaying || syncing) {
                //Shows ongoing notification when synchronizing the blockchain
                val notification = createNetworkSyncNotification(blockchainState)
                nm!!.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification)
            } else if (mixingStatus == MixingStatus.MIXING || mixingStatus == MixingStatus.PAUSED || mixingStatus == MixingStatus.FINISHING) {
                log.info("foreground service: {}", foregroundService)
                if (foregroundService == ForegroundService.NONE) {
                    log.info("foreground service not active, create notification")
                    startForegroundAndCatch(createCoinJoinNotification())
                    foregroundService = ForegroundService.COINJOIN_MIXING
                } else {
                    log.info("foreground service active, update notification")
                    val notification = createCoinJoinNotification()
                    nm!!.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification)
                }
            }
        }
        this.blockchainState = blockchainState
        this.mixingStatus = mixingStatus
    }

    private fun percentageSync(): Int {
        return syncPercentage
    }

    private var handleContactPaymentsJob: Job? = null

    private fun handleContactPayments(tx: Transaction) {
        if (blockchainState?.replaying != true) {
            handleContactPaymentsJob?.cancel()
            handleContactPaymentsJob = serviceScope.launch {
                delay(TimeUnit.SECONDS.toMillis(1)) // debounce delay, 1 second
                platformRepo.updateFrequentContacts(tx)
            }
        }
    }

    private fun updateAppWidget() {
        val balance = application.getWalletBalance()
        WalletBalanceWidgetProvider.updateWidgets(this@BlockchainServiceImpl, balance)
    }

    fun forceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(this, BlockchainServiceImpl::class.java)
            ContextCompat.startForegroundService(this, intent)
            // call startForeground just after startForegroundService.
            startForegroundAndCatch(createNetworkSyncNotification())
        }
    }

    private val preBlocksDownloadListener = PreBlocksDownloadListener { peer ->
        log.info("onPreBlocksDownload using peer {}", peer)
        platformSyncService.preBlockDownload(peerGroup!!.preBlockDownloadFuture)
    }

    private fun loadStream(filename: String): InputStream? {
        return try {
            assets.open(filename)
        } catch (x: IOException) {
            log.warn("cannot load the bootstrap stream: {}", x.message)
            null
        }
    }

    private fun closeStream(mnlistinfoBootStrapStream: InputStream?) {
        if (mnlistinfoBootStrapStream != null) {
            try {
                mnlistinfoBootStrapStream.close()
            } catch (x: IOException) {
                //do nothing
            }
        }
    }

    // TODO: should we have a backup blockchain file?
    //    private NewBestBlockListener newBestBlockListener = block -> {
    //        try {
    //            backupBlockStore.put(block);
    //        } catch (BlockStoreException x) {
    //            throw new RuntimeException(x);
    //        }
    //    };
    @Throws(BlockStoreException::class)
    private fun verifyBlockStore(store: BlockStore?): Boolean {
        var cursor = store!!.chainHead
        for (i in 0..9) {
            cursor = cursor!!.getPrev(store)
            if (cursor == null || cursor.header == Constants.NETWORK_PARAMETERS.genesisBlock) {
                break
            }
        }
        return true
    }

    private fun verifyBlockStore(
        store: BlockStore?,
        scheduledExecutorService: ScheduledExecutorService
    ): Boolean {
        return try {
            val future = scheduledExecutorService.schedule<Boolean>(
                { verifyBlockStore(store) }, 100, TimeUnit.MILLISECONDS
            )
            future[1, TimeUnit.SECONDS]
        } catch (e: Exception) {
            log.warn("verification of blockstore failed:", e)
            false
        }
    }

    // TODO: should we have a backup blockchain file?
    //    public static void copyFile(File source, File destination) throws IOException {
    //        try (FileChannel sourceChannel = new FileInputStream(source).getChannel();
    //             FileChannel destChannel = new FileOutputStream(destination).getChannel()) {
    //            sourceChannel.transferTo(0, sourceChannel.size(), destChannel);
    //        }
    //    }
    //
    //
    //    private void replaceBlockStore(BlockStore a, File aFile, BlockStore b, File bFile) throws BlockStoreException {
    //        try {
    //            a.close();
    //            b.close();
    //            copyFile(bFile, aFile);
    //        } catch (IOException e) {
    //            throw new RuntimeException(e);
    //        }
    //    }
    @Throws(BlockStoreException::class)
    private fun verifyBlockStores() {
        val scheduledExecutorService: ScheduledExecutorService = ScheduledThreadPoolExecutor(1)
        log.info("verifying backupBlockStore")
        //        boolean verifiedBackupBlockStore = false;
        var verifiedHeaderStore = false
        var verifiedBlockStore = false
        //        if (!(verifiedBackupBlockStore = verifyBlockStore(backupBlockStore, scheduledExecutorService))) {
//            log.info("backupBlockStore verification failed");
//        }
        log.info("verifying headerStore")
        if (!verifyBlockStore(headerStore, scheduledExecutorService).also {
                verifiedHeaderStore = it
            }) {
            log.info("headerStore verification failed")
        }
        log.info("verifying blockStore")
        if (!verifyBlockStore(blockStore, scheduledExecutorService).also {
                verifiedBlockStore = it
            }) {
            log.info("blockStore verification failed")
        }
        // TODO: should we have a backup blockchain file?
//        if (!verifiedBlockStore) {
//            if (verifiedBackupBlockStore &&
//                    !backupBlockStore.getChainHead().getHeader().getHash().equals(Constants.NETWORK_PARAMETERS.getGenesisBlock().getHash())) {
//                log.info("replacing blockStore with backup");
//                replaceBlockStore(blockStore, blockChainFile, backupBlockStore, backupBlockChainFile);
//                log.info("reloading blockStore");
//                blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
//                blockStore.getChainHead(); // detect corruptions as early as possible
//                log.info("reloading backup blockchain file");
//                backupBlockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
//                backupBlockStore.getChainHead(); // detect corruptions as early as possible
//                verifyBlockStores();
//            } /*else if (verifiedHeaderStore) {
//                log.info("replacing blockStore with header");
//                replaceBlockStore(blockStore, blockChainFile, headerStore, headerChainFile);
//                log.info("reloading blockStore");
//                blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
//                blockStore.getChainHead(); // detect corruptions as early as possible
//                log.info("reloading header file");
//                headerStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, headerChainFile);
//                headerStore.getChainHead(); // detect corruptions as early as possible
//                verifyBlockStores();
//            } else*/ {
//                // get blocks from platform here...
//                throw new BlockStoreException("can't verify and recover");
//            }
//        }
        log.info("blockstore files verified: {}, {}", verifiedBlockStore, verifiedHeaderStore)
    }
}
