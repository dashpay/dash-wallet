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
@file:OptIn(FlowPreview::class)
package de.schildbach.wallet.service

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
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
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.transactions.WalletObserver.Companion.CONFIRMED_DEPTH
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.ui.OnboardingActivity.Companion.createIntent
import de.schildbach.wallet.ui.dashpay.OnPreBlockProgressListener
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis.OfflineAnalyzer
import de.schildbach.wallet.util.AnrException
import de.schildbach.wallet.util.BlockchainStateUtils
import de.schildbach.wallet.util.CrashReporter
import de.schildbach.wallet.util.FriendKeyChainLookahead
import de.schildbach.wallet.util.ThrottledRunner
import de.schildbach.wallet.util.ThrottlingWalletChangeListener
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import org.bitcoinj.core.listeners.TimeoutError
import org.bitcoinj.core.listeners.TimeoutErrorListener
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
import org.dash.wallet.common.data.BlockStoreLastFix
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.data.BlockchainServiceConfig.Companion.BLOCKCHAIN_STORE_LAST_FIX
import org.dash.wallet.common.data.BlockchainServiceConfig.Companion.BLOCKCHAIN_STORE_MEMORY_FAILURE
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import de.schildbach.wallet.transactions.TransactionUtils.getWalletAddressOfReceived
import de.schildbach.wallet.transactions.NotFromAddressTxFilter
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
import org.bitcoinj.core.listeners.NewBestBlockListener
import java.util.concurrent.ConcurrentHashMap
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.transactions.toTxInfo
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

/**
 * @author Andreas Schildbach
 * @author Eric Britten
 */
@AndroidEntryPoint
class BlockchainServiceImpl : LifecycleService(), BlockchainService {

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            log.info("App moved to foreground")
            isAppInBackground = false
        }

        override fun onStop(owner: LifecycleOwner) {
            log.info("App moved to background")
            isAppInBackground = true
        }
    }

    companion object {
        private const val MINIMUM_PEER_COUNT = 16
        // The idle thresholds, the history ring size and the idle RULE now
        // live in SyncActivityIdleDetector.kt (same package) so they are
        // host-JVM testable and shared by both engines' sample sources.
        private const val APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        private const val BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS
        /** Peer connect/disconnect bursts collapse to one notification update per second. */
        private const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = DateUtils.SECOND_IN_MILLIS
        private val TX_EXCHANGE_RATE_TIME_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(180)
        private val log = LoggerFactory.getLogger(BlockchainServiceImpl::class.java)
        const val START_AS_FOREGROUND_EXTRA = "start_as_foreground"
        var cleanupDeferred: CompletableDeferred<Unit>? = null
        private val isCleaningUp = AtomicBoolean(false)

        // TEST ONLY: Reduce timeout for testing onTimeout callback
        // Set to 0 to disable test timeout (use Android's default 6-hour limit)
        // Set to 1-2 minutes for testing the timeout behavior
        private val TEST_TIMEOUT_MINUTES = 0L // Change to 0 to disable
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
    @Inject lateinit var walletTransactionMetadataProvider: WalletTransactionMetadataProvider

    @Inject lateinit var platformSyncService: PlatformSyncService
    @Inject lateinit var identityRepo: IdentityRepository
    @Inject lateinit var platformRepo: PlatformRepo
    @Inject lateinit var sdkQuorumDataSource: de.schildbach.wallet.service.platform.sdk.SdkQuorumDataSource
    @Inject lateinit var topUpRepository: TopUpRepository

    @Inject lateinit var  packageInfoProvider: PackageInfoProvider

    @Inject lateinit var  connectivityManager: ConnectivityManager

    @Inject
    lateinit var blockchainStateDataProvider: BlockchainStateDataProvider

    @Inject
    lateinit var dashSystemService: DashSystemService

    @Inject lateinit var serviceConfig: BlockchainServiceConfig
    @Inject lateinit var analyticsService: AnalyticsService
    @Inject lateinit var securityFunctions: AuthenticationManager
    @Inject lateinit var cutoverCoordinator: de.schildbach.wallet.service.platform.sdk.CutoverCoordinator
    @Inject lateinit var txDisplayCacheDao: de.schildbach.wallet.database.dao.TxDisplayCacheDao
    @Inject lateinit var dashPayConfig: de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
    @Inject lateinit var l1ShadowSyncService: de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
    @Inject lateinit var dashjDiagnosticSyncState: DashjDiagnosticSyncState

    /**
     * Phase 5d cutover gate, resolved ONCE per launch just before
     * [onCreateCompleted] (so [checkService], which awaits it, always sees a
     * settled value). Default true = today's behavior; only a committed
     * cutover (CUT_OVER/SETTLED) flips it false, holding the dashj L1 engine
     * so the SDK owns L1. Reversible via the ROLLBACK debug trigger.
     */
    @Volatile
    private var dashjEngineMayStart = true

    /**
     * DIAGNOSTIC (Tools toggle,
     * [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.DASHJ_SYNC_DIAGNOSTIC]):
     * whether the cutover would OTHERWISE hold dashj this launch — i.e. the SDK
     * owns L1 (committed). Captured separately from [dashjEngineMayStart]
     * because the diagnostic OR-s dashj back on, which would otherwise erase
     * this signal. True + [dashjSyncDiagnostic] is the Option-B isolation
     * condition in [updateBlockchainState]. False (pre-cutover / flag off) ⇒
     * everything below is inert.
     */
    @Volatile
    private var dashjHeldByCutover = false

    /** Whether the dashj-sync diagnostic toggle was on when the gate resolved. */
    @Volatile
    private var dashjSyncDiagnostic = false

    /** Last parity verdict published to the diagnostic holder — de-dupes the 100% log. */
    @Volatile
    private var lastDiagnosticParity: DashjDiagnosticSyncState.Parity? = null

    /**
     * Wall-clock moment dashj's diagnostic percent first reached 100 this
     * stretch (null while below 100). The displayed 100% and the verdict are
     * WITHHELD until the shadow harness produces a parity report stamped
     * at/after this moment — i.e. a comparison provably computed against the
     * caught-up dashj state (owner's spec: no red/purple/green until the
     * status reaches 100, and no 100 until the parity check is complete).
     * Same `System.currentTimeMillis` clock as `ParityReport.timestampMs`.
     */
    @Volatile
    private var dashjCaughtUpAtMs: Long? = null

    /** Last args passed to [publishDashjDiagnostic] — lets a new parity report re-publish. */
    @Volatile
    private var lastDiagnosticRawPercent = 0

    @Volatile
    private var lastDiagnosticStage: PeerGroup.SyncStage? = null

    /**
     * FIX 1 guard: the post-cutover identity/contacts discovery hook
     * ([maybeRecoverIdentityPostCutover]) fires at most once — reset only if the
     * discovery attempt throws, so a later synced notification can retry.
     */
    private val postCutoverIdentityRecoveryTriggered = AtomicBoolean(false)

    /**
     * The SDK-corrected signed net value for [tx] when the cutover UI cache
     * ([txDisplayCacheDao]) holds a row for it, else the supplied dashj [fallback].
     *
     * dashj mis-values SDK-authored sends (it sees only the +change output and reports a positive
     * "received" value), so the received-coins notification would both fire on a send and show the
     * wrong amount. The tx_display_cache row carries the engine-corrected signed net (negative =
     * sent), keyed by lowercase display-hex txid. Absent → dashj value, so pre-cutover / non-SDK
     * txs are byte-for-byte unchanged.
     *
     * Runs synchronously on a bitcoinj wallet-listener thread (never the main thread) and fails
     * soft to [fallback] on ANY error, keeping the notification path non-blocking and robust.
     */
    private fun correctedNetValue(tx: Transaction, fallback: Coin): Coin {
        return try {
            val cached = txDisplayCacheDao.getValueSatoshisByIdSync(tx.txId.toString().lowercase())
            if (cached != null) Coin.valueOf(cached) else fallback
        } catch (t: Throwable) {
            log.warn("tx_display_cache lookup failed for {}, using dashj value", tx.txId, t)
            fallback
        }
    }

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

    /**
     * NOTIFICATION LANE — a dedicated background thread for every
     * NotificationManager binder call this service makes repeatedly.
     *
     * WHY: NotificationManager.notify/cancel are synchronous binder IPCs into
     * system_server. On a loaded device that call can BLOCK for seconds — the
     * mainnet tester's session had all 7 ANR stacks parked inside
     * NotificationManager.cancel on the MAIN thread, driven by 233
     * peer-disconnect events. Nothing about a status-bar notification needs
     * the main thread, so all repeated notification traffic goes through this
     * single background lane instead (one thread also serializes notify/cancel
     * ordering better than mixed main/coroutine callers ever did).
     */
    private val notificationHandlerThread = HandlerThread("notification-lane").apply { start() }
    private val notificationHandler = Handler(notificationHandlerThread.looper)
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

    // Settings to bypass dashj default dns seeds
    private val seedPeerDiscovery = SeedPeers(Constants.NETWORK_PARAMETERS)
    private val dnsDiscovery = DnsDiscovery(Constants.DNS_SEED, Constants.NETWORK_PARAMETERS)
    var peerDiscoveryList = ArrayList<PeerDiscovery>(2)
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var syncPercentage = 0 // 0 to 100%
    private var balance = Coin.ZERO
    private var foregroundService = ForegroundService.NONE

    // Background state tracking for Android 15 thread optimization
    private var isAppInBackground = false

    // KeyStore Status
    private var isKeyStoreHealthy = true

    // Risk Analyser for Transactions that is PeerGroup Aware
    private var riskAnalyzer: AllowLockTimeRiskAnalysis.Analyzer? = null
    private var defaultRiskAnalyzer = DefaultRiskAnalysis.FACTORY
    private val crowdnodeFilters = listOf(
        NotFromAddressTxFilter(
            Address.fromBase58(Constants.NETWORK_PARAMETERS, getCrowdNodeAddress(Constants.NETWORK_PARAMETERS.id))
        )
    )
    private val crowdnodeNeutralFilters = listOf(
        CrowdNodeWithdrawalReceivedTx(Constants.NETWORK_PARAMETERS.id)
    )
    private val depositReceivedResponse =
        CrowdNodeDepositReceivedResponse(Constants.NETWORK_PARAMETERS.id)
    private var apiConfirmationHandler: CrowdNodeAPIConfirmationHandler? = null
    private fun handleMetadata(tx: Transaction) {
        serviceScope.launch {
            walletTransactionMetadataProvider.syncTransaction(tx)
        }
    }
    private val currentBlock = MutableStateFlow<StoredBlock?>(null)

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
                            // Set the rate on the in-memory tx only. Do NOT saveWallet() here:
                            // this callback runs inline under the wallet lock while commitTx() holds
                            // it, and a full save of a large wallet takes seconds — doing it here
                            // blocked the send path (purchase confirm dialog hung, tx not broadcast
                            // until restart) and, firing for every CoinJoin mixing tx, starved the
                            // lock. The exchange rate is non-critical and is persisted separately in
                            // the transaction metadata table.
                            tx.exchangeRate = ExchangeRate(Coin.COIN, exchangeRate.fiat.toDashjFiat())
                        }
                    } catch (e: Exception) {
                        log.error("Failed to get exchange rate", e)
                    }
                }
                transactionsReceived.incrementAndGet()
                val address = getWalletAddressOfReceived(tx, wallet)
                // Prefer the SDK-corrected net for the notification amount; falls back to dashj
                // for pre-cutover / non-SDK txs (see correctedNetValue).
                val amount = correctedNetValue(tx, tx.getValue(wallet))
                val confidenceType = tx.confidence.confidenceType
                val isRestoringBackup = application.configuration.isRestoringBackup
                handler.post {
                    val isReplayedTx =
                        confidenceType == TransactionConfidence.ConfidenceType.BUILDING && (replaying || isRestoringBackup)
                    if (!isReplayedTx) {
                        if (depositReceivedResponse.matches(tx.toTxInfo(wallet, Constants.NETWORK_PARAMETERS))) {
                            notificationService.showNotification(
                                "deposit_received",
                                getString(R.string.crowdnode_deposit_received),
                                null,
                                null,
                                Intent(this@BlockchainServiceImpl, StakingActivity::class.java),
                                null
                            )
                        } else if (
                            apiConfirmationHandler != null &&
                            apiConfirmationHandler!!.matches(tx.toTxInfo(wallet, Constants.NETWORK_PARAMETERS))
                        ) {
                            apiConfirmationHandler!!.handle(tx.toTxInfo(wallet, Constants.NETWORK_PARAMETERS))
                        } else if (passFilters(tx, wallet)) {
                            // resolveLabel is a ContentProvider query and
                            // nm.notify a binder IPC — notification lane, not
                            // main. All coins-received notification state is
                            // mutated on that single lane (see the cancel
                            // action in onStartCommand).
                            notificationHandler.post { notifyCoinsReceived(address, amount, tx.exchangeRate) }
                        }
                    }
                }
                maybeAddMonitoring(tx, wallet)
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
                    topUpRepository.handleSentAssetLockTransaction(cftx, tx.updateTime.time)

                    // TODO: if we detect a username creation that we haven't processed, should we?
                }
                maybeAddMonitoring(tx, wallet)
                handleMetadata(tx)
                updateAppWidget()
            }

            private fun passFilters(tx: Transaction, wallet: Wallet): Boolean {
                // Prefer the SDK-corrected net so an SDK send mis-read by dashj as a positive
                // "received" value does not trip the received-coins gate; dashj fallback otherwise.
                val amount = correctedNetValue(tx, tx.getValue(wallet))
                val isReceived = amount.signum() > 0
                if (!isReceived) {
                    return false
                }
                var passFilters = crowdnodeFilters.any { it.matches(tx) }
                if (!passFilters) {
                    val txInfo = tx.toTxInfo(wallet, Constants.NETWORK_PARAMETERS)
                    passFilters = crowdnodeNeutralFilters.any { it.matches(txInfo) }
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
    @SuppressLint("WrongConstant")
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

    private val oldTransactionsToMonitor = ConcurrentHashMap<Sha256Hash, Transaction>()
    private var txConfidenceJob: Job? = null

    private fun updateTxConfidence() {
        application.wallet?.let { wallet ->
            val txesToRemove = arrayListOf<Sha256Hash>()
            oldTransactionsToMonitor.forEach { (_, tx) ->
                val transactionConfidence = tx.getConfidence(wallet.context)
                val shouldStopListening = if (transactionConfidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
                    transactionConfidence.depthInBlocks = wallet.lastBlockSeenHeight - transactionConfidence.appearedAtChainHeight + 1
                    log.info(
                        "tx {}; {} == {}",
                        transactionConfidence.transactionHash,
                        transactionConfidence.depthInBlocks,
                        wallet.lastBlockSeenHeight - transactionConfidence.appearedAtChainHeight + 1
                    )

                    val requiredDepth = if (tx.isCoinBase) {
                        wallet.params.spendableCoinbaseDepth
                    } else {
                        CONFIRMED_DEPTH
                    }

                    val result = transactionConfidence.depthInBlocks >= requiredDepth
                    transactionConfidence.queueListeners(TransactionConfidence.Listener.ChangeReason.DEPTH)
                    result
                } else {
                    false
                }

                if (shouldStopListening) {
                    txesToRemove.add(transactionConfidence.transactionHash)
                }
            }
            txesToRemove.forEach {
                oldTransactionsToMonitor.remove(it)
            }
        }
    }

    private fun stopMonitoringOlderTransactions(wallet: Wallet) {
        if (wallet.isNotifyTxOnNextBlock) {
            return
        }
        log.info("stop monitoring {} old transactions", oldTransactionsToMonitor.size)
        oldTransactionsToMonitor.clear()
    }

    private fun monitorOlderTransactions(wallet: Wallet) {
        if (wallet.isNotifyTxOnNextBlock) {
            return
        }
        val transactions = wallet.getTransactions(true)
        transactions.forEach { tx ->
            maybeAddMonitoring(tx, wallet)
        }
        log.info("monitoring {} old transactions", oldTransactionsToMonitor.size)
    }

    private fun maybeAddMonitoring(tx: Transaction, wallet: Wallet) {
        if (wallet.isNotifyTxOnNextBlock) {
            return
        }
        val confidence = tx.getConfidence(wallet.context)
        val shouldMonitor = when (confidence.confidenceType) {
            TransactionConfidence.ConfidenceType.PENDING -> true
            TransactionConfidence.ConfidenceType.BUILDING -> when {
                tx.isCoinBase -> confidence.depthInBlocks < wallet.params.spendableCoinbaseDepth
                else -> confidence.depthInBlocks < 6
            }

            else -> false
        }
        if (shouldMonitor) {
            oldTransactionsToMonitor[tx.txId] = tx
        }
    }

    private inner class PeerConnectivityListener : PeerConnectedEventListener,
        PeerDisconnectedEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private var peerCount = 0
        val stopped = AtomicBoolean(false)

        /** The latest peer count — whatever it is when the throttled update runs, wins. */
        private val latestPeerCount = AtomicInteger(0)

        /**
         * OFF-MAIN + COALESCED notification updates. The old code posted one
         * MAIN-thread NotificationManager call per peer event; a flapping
         * network delivered 233 of them and the blocked binder call
         * (system_server under load) was the parked frame in all 7 of the
         * mainnet tester's ANR stacks. The status bar does not need 233
         * updates: at most one per second, on the notification lane, reading
         * whatever the peer count is by then.
         */
        private val throttledUpdate = ThrottledRunner(
            NOTIFICATION_UPDATE_MIN_INTERVAL_MS,
            { runnable, delayMs -> notificationHandler.postDelayed(runnable, delayMs) },
            { SystemClock.uptimeMillis() }
        ) { updateNotificationAndBroadcast() }

        init {
            config.registerOnSharedPreferenceChangeListener(this)
        }

        fun stop() {
            stopped.set(true)
            config.unregisterOnSharedPreferenceChangeListener(this)
            // The final cancel is a binder call too — keep it off the caller
            // (onDestroy runs on main) and behind any in-flight notify, so the
            // notification cannot be resurrected after the cancel.
            notificationHandler.post {
                try {
                    nm!!.cancel(Constants.NOTIFICATION_ID_CONNECTED)
                } catch (t: Throwable) {
                    log.warn("failed to cancel connectivity notification on stop", t)
                }
            }
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
            // The network-status flip is cheap in-memory state — keep it
            // immediate and per-event.
            val networkStatus = blockchainStateDataProvider.getNetworkStatus()
            if (numPeers > 0 && networkStatus == NetworkStatus.CONNECTING) blockchainStateDataProvider.setNetworkStatus(
                NetworkStatus.CONNECTED
            ) else if (numPeers == 0 && networkStatus == NetworkStatus.DISCONNECTING) blockchainStateDataProvider.setNetworkStatus(
                NetworkStatus.DISCONNECTED
            )
            latestPeerCount.set(numPeers)
            throttledUpdate.schedule()
        }

        /** Runs on the notification lane, at most once per interval. */
        @SuppressLint("WrongConstant")
        private fun updateNotificationAndBroadcast() {
            if (stopped.get()) return
            val numPeers = latestPeerCount.get()
            try {
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
            } catch (t: Throwable) {
                // A notification must never take the peer machinery down.
                log.warn("connectivity notification update failed", t)
            }

            // send broadcast (LocalBroadcastManager delivers on its own
            // executor — safe from this thread)
            broadcastPeerState(numPeers)
        }
    }

    private abstract inner class MyDownloadProgressTracker
        : DownloadProgressTracker(Constants.SYNC_FLAGS.contains(MasternodeSync.SYNC_FLAGS.SYNC_BLOCKS_AFTER_PREPROCESSING)), OnPreBlockProgressListener

    private val timeoutErrorListener = TimeoutErrorListener { error, peer ->
        log.info("handling timeout error due to $error from $peer")
        when (error) {
            TimeoutError.BLOCKSTORE_MEMORY_ACCESS -> {
                serviceScope.launch {
                    if (serviceConfig.get(BLOCKCHAIN_STORE_MEMORY_FAILURE) != true) {
                        serviceConfig.set(BLOCKCHAIN_STORE_MEMORY_FAILURE, true)
                        log.warn("BLOCKSTORE_MEMORY_ACCESS timeout detected - forcing shutdown and scheduling restart in 1 minute")
                        analyticsService.logError(Exception("Blockstore memory stalled"))
                        rescheduleService()

                        // Force immediate shutdown
                        withContext(Dispatchers.Main) {
                            stopSelf()
                        }
                    }
                }
            }
            TimeoutError.UNKNOWN -> {

            }
            else -> {}
        }
    }

    @SuppressLint("WrongConstant")
    private fun rescheduleService() {
        // Schedule restart in 1 minute
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val serviceIntent = Intent(
            application,
            BlockchainServiceImpl::class.java
        )
        val alarmIntent: PendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            serviceIntent.putExtra(START_AS_FOREGROUND_EXTRA, true)
            PendingIntent.getForegroundService(
                application, 0, serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                application, 0, serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        // alarmManager.cancel(alarmIntent)

        val restartTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            restartTime,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            alarmIntent
        )

        log.info("Scheduled service restart in 1 minute at {}", Date(restartTime))
    }

    private val blockchainDownloadListener: MyDownloadProgressTracker =
        object : MyDownloadProgressTracker() {
            val watch = Stopwatch.createUnstarted()
            private val lastMessageTime = AtomicLong(0)
            private var throttleDelay: Long = -1

            override fun onHeadersDownloadStarted(peer: Peer?, blocksLeft: Int) {
                super.onHeadersDownloadStarted(peer, blocksLeft)
            }

            override fun onChainDownloadStarted(peer: Peer?, blocksLeft: Int) {
                super.onChainDownloadStarted(peer, blocksLeft)
                if (!watch.isRunning) {
                    watch.start();
                }
            }

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
             *  of the chain downloaded is 100.0% (completely done)
             */
            override fun doneDownload() {
                super.doneDownload()
                if (watch.isRunning) {
                    watch.stop()
                }
                log.info("DoneDownload {} in {}", syncPercentage, watch)
                // if the chain is already synced from a previous session, then syncPercentage = 0
                // set to 100% so that observers will see that sync is completed
                syncPercentage = 100
                updateBlockchainState()
                if (Constants.SUPPORTS_PLATFORM) {
                    serviceScope.launch {
                        identityRepo.updateFrequentContacts()
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
                    syncPercentage = max(syncPercentage.toDouble(), startPreBlockPercent + increment).toInt()
                    log.info("PreBlockDownload: " + syncPercentage + "%..." + stage.name)
                    postOrPostDelayed()
                }
                lastPreBlockStage = stage
            }
        }
    private var networkCallbackRegistered = false
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val availableNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private inner class NetworkCallbackImpl : ConnectivityManager.NetworkCallback() {
        init {
            // Setup security health monitoring
            securityFunctions.observeHealth().observe(this@BlockchainServiceImpl) { status ->
                isKeyStoreHealthy = status.isHealthy
                if (isKeyStoreHealthy) {
                    impediments.remove(Impediment.SECURITY)
                } else {
                    impediments.add(Impediment.SECURITY)
                }
                updateBlockchainStateImpediments()
                check()
            }
        }

        override fun onAvailable(network: Network) {
            serviceScope.launch {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

                if (log.isInfoEnabled) {
                    val s = StringBuilder("network available: ")
                        .append(network)
                    if (capabilities != null) {
                        s.append(", hasInternet: ").append(hasInternet)
                        s.append(", validated: ").append(hasValidated)
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            s.append(", type: WiFi")
                        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            s.append(", type: Cellular")
                        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            s.append(", type: Ethernet")
                        }
                    }
                    log.info(s.toString())
                }

                if (hasInternet && hasValidated) {
                    availableNetworks.add(network)
                    impediments.remove(Impediment.NETWORK)
                    updateBlockchainStateImpediments()
                    check()
                }
            }
        }

        override fun onLost(network: Network) {
            serviceScope.launch {
                availableNetworks.remove(network)
                log.info("network lost: $network, remaining networks: ${availableNetworks.size}")

                // Only add impediment if no suitable networks remain
                if (availableNetworks.isEmpty()) {
                    impediments.add(Impediment.NETWORK)
                    updateBlockchainStateImpediments()
                    check()
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            serviceScope.launch {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                if (log.isInfoEnabled) {
                    val s = StringBuilder("network capabilities changed: ")
                        .append(network)
                        .append(", hasInternet: ").append(hasInternet)
                        .append(", validated: ").append(hasValidated)
                    log.info(s.toString())
                }

                if (hasInternet && hasValidated) {
                    impediments.remove(Impediment.NETWORK)
                } else {
                    impediments.add(Impediment.NETWORK)
                }
                updateBlockchainStateImpediments()
                check()
            }
        }

        override fun onUnavailable() {
            super.onUnavailable()
            serviceScope.launch {
                log.info("network unavailable - no network satisfies request")
                impediments.add(Impediment.NETWORK)
                updateBlockchainStateImpediments()
                check()
            }
        }

        fun check() {
            serviceScope.launch {
                try {
                    // make sure that onCreate is finished
                    onCreateCompleted.await()
                    log.info("acquiring check() mutex")

                    // Use withLock to guarantee mutex release even if coroutine is cancelled
                    checkMutex.withLock {
                        try {
                            checkService()
                        } catch (e: Exception) {
                            log.error("checkService() failed with exception", e)
                            // Don't rethrow - we want to ensure clean mutex release
                        }
                    }
                    log.info("releasing check() mutex")
                } catch (e: Exception) {
                    log.error("check() failed", e)
                }
            }
        }

        @SuppressLint("Wakelock")
        private fun checkService() {
            log.info("check()")
            val wallet = application.wallet
            if (impediments.isEmpty() && peerGroup == null) {
                if (!dashjEngineMayStart) {
                    // Phase 5d cutover committed: the SDK owns L1 this launch,
                    // so the dashj peergroup must never start (never two live
                    // SPV engines for one user). Reversible via ROLLBACK.
                    log.info("cutover committed — holding the dashj L1 engine; SDK owns L1 this launch")
                    return
                }
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
                // FUNDS GATE: the DashPay friend-key-chain lookahead is derived
                // off the wallet-load critical path (FriendKeyChainLookahead —
                // it is what made a 2.5MB wallet take minutes to open). The
                // bloom filter / watched-script set peers ever see is computed
                // from `addWallet` onwards, so the deferred keys must all exist
                // BEFORE this line: that makes received-funds detection
                // identical to deriving them inside the parse. This runs on the
                // service's background check() thread, never the main thread.
                if (!FriendKeyChainLookahead.awaitComplete()) {
                    log.error(
                        "starting the peergroup with the DashPay friend key chain lookahead incomplete " +
                            "({} of {} chains) — a contact payment beyond the derived window may need a rescan",
                        FriendKeyChainLookahead.completedCount(), FriendKeyChainLookahead.deferredCount()
                    )
                } else if (FriendKeyChainLookahead.deferredCount() > 0) {
                    log.info(
                        "DashPay friend key chain lookahead complete before peergroup start: {} chains in {}ms",
                        FriendKeyChainLookahead.completedCount(), FriendKeyChainLookahead.completionMs()
                    )
                }
                peerGroup!!.addWallet(wallet)
                dashSystemService.system.addWallet(wallet)
                peerGroup!!.setUserAgent(Constants.USER_AGENT, packageInfoProvider.versionName)
                log.info("Adding PeerConnectivityListener to peerGroup: listener={}, stopped={}",
                    peerConnectivityListener, peerConnectivityListener?.stopped?.get())
                peerGroup!!.addConnectedEventListener(peerConnectivityListener)
                peerGroup!!.addDisconnectedEventListener(peerConnectivityListener)
                peerGroup!!.addTimeoutErrorListener(executor, timeoutErrorListener)
                log.info("Successfully added peer listeners to peerGroup")
                val maxConnectedPeers = application.maxConnectedPeers()
                val trustedPeerHost = config.trustedPeerHost
                val hasTrustedPeer = trustedPeerHost != null
                val connectTrustedPeerOnly = hasTrustedPeer && config.trustedPeerOnly
                peerGroup!!.maxConnections = if (connectTrustedPeerOnly) 1 else maxConnectedPeers
                peerGroup!!.maxStalls = 1000
                peerGroup!!.setStallThreshold(30, 5 * Block.HEADER_SIZE)
                peerGroup!!.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS)
                peerGroup!!.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS.toLong())
                // REVERTED to dashj's own default (false) in 11.10.58 — this was
                // true since 11.10.x. The tester's session hit a repeated
                // Peer.processHeaders:800 IllegalStateException → peer-close
                // cascade: a pre-existing dashj headers-download state-machine
                // bug (a STALE headersDownloadedFuture flips vDownloadHeaders
                // while a getheaders2 request is still in flight; reported
                // upstream as dashj PR #132 in 2020, no fix in 22.0.4 or
                // master). Compressed headers don't CAUSE the race, but they
                // enlarge its window 4x (MAX_HEADERS 8000 vs 2000) and add the
                // processHeaders2 entry path. false removes that path entirely
                // and restores the 11.9-and-earlier configuration.
                peerGroup!!.isUseCompressedHeaders = false
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
                stopPeerGroup()
            }
        }

        /**
         * Full dashj peergroup teardown (extracted from [checkService]'s stop
         * branch so it can be reused unchanged). Callers hold [checkMutex] and
         * have already confirmed [peerGroup] != null.
         */
        fun stopPeerGroup() {
            val wallet = application.wallet
            blockchainStateDataProvider.setNetworkStatus(NetworkStatus.NOT_AVAILABLE)
            log.info("stopping peergroup")
            peerGroup!!.removeDisconnectedEventListener(peerConnectivityListener)
            peerGroup!!.removeConnectedEventListener(peerConnectivityListener)
            peerGroup!!.removePreBlocksDownloadedListener(preBlocksDownloadListener)
            peerGroup!!.removeWallet(wallet)
            dashSystemService.system.removeWallet(wallet)
            platformSyncService.removePreBlockProgressListener(blockchainDownloadListener)
            peerGroup!!.stopAsync()
            try {
                dashSystemService.system.close()
            } catch (e: Exception) {
                log.error("Error closing dash system service", e)
            }
            // use the offline risk analyzer
            if (wallet != null) {
                wallet.riskAnalyzer =
                    OfflineAnalyzer(config.bestHeightEver, System.currentTimeMillis() / 1000)
            }
            try {
                riskAnalyzer?.shutdown()
            } catch (e: Exception) {
                log.error("Error shutting down risk analyzer", e)
            }
            peerGroup = null
            log.debug("releasing wakelock")
            if (wakeLock!!.isHeld) {
                wakeLock!!.release()
            }
        }

        /**
         * FIX 2: the DASHJ_SYNC_DIAGNOSTIC toggle (Tools) must take effect on a
         * LIVE foreground service. [WalletApplication.restartBlockchainService]
         * can't be relied on to destroy/recreate a foreground service, so
         * [onCreate]'s one-shot gate resolution never re-runs and the peergroup
         * never (un)holds. This re-resolves the SAME gate (mirrors the onCreate
         * computation) under [checkMutex] and actually starts/stops dashj.
         *
         * Inert unless the cutover is committed ([dashjHeldByCutover]): pre-cutover
         * dashj is already the primary L1 engine and the flag only mirrors its
         * progress into the diagnostic holder, so nothing about whether the
         * peergroup runs changes — byte-for-byte as before.
         */
        fun onDashjDiagnosticChanged(enabled: Boolean) {
            serviceScope.launch {
                onCreateCompleted.await()
                checkMutex.withLock {
                    try {
                        // Mirror onCreate's gate resolution (coordinatorAllowsDashj
                        // is fixed for the launch; only the diagnostic flag changes).
                        val coordinatorAllowsDashj = runCatching { cutoverCoordinator.dashjEngineMayStart() }
                            .getOrDefault(true)
                        val newEngineMayStart = coordinatorAllowsDashj || enabled
                        val changed = enabled != dashjSyncDiagnostic ||
                            newEngineMayStart != dashjEngineMayStart

                        dashjSyncDiagnostic = enabled
                        dashjHeldByCutover = !coordinatorAllowsDashj
                        dashjEngineMayStart = newEngineMayStart
                        if (!enabled) {
                            dashjDiagnosticSyncState.reset()
                            lastDiagnosticParity = null
                            dashjCaughtUpAtMs = null
                        }

                        if (!changed) return@withLock
                        log.info(
                            "dashj-sync-diagnostic toggle -> {}; re-resolved dashjEngineMayStart={} " +
                                "(coordinatorAllowsDashj={}, dashjHeldByCutover={})",
                            enabled, dashjEngineMayStart, coordinatorAllowsDashj, dashjHeldByCutover
                        )

                        if (!dashjHeldByCutover) {
                            // Pre-cutover: dashj is the primary engine regardless of
                            // this flag; do not disturb the running peergroup.
                            return@withLock
                        }

                        if (dashjEngineMayStart && peerGroup == null) {
                            // Un-hold: start dashj as a backup / parity check.
                            // First honor the tester-chosen "sync from date"
                            // (diagnostic path only — we are here exactly when
                            // dashjSyncDiagnostic && dashjHeldByCutover): the
                            // peergroup is down and checkMutex is held, so the
                            // stores/chains can be swapped safely. Then reuse the
                            // normal start path (starts only if impediments empty).
                            val fromSecs = runCatching { dashPayConfig.getDashjSyncDiagnosticFromSecs() }
                                .getOrDefault(0L)
                            application.wallet?.let { maybeRecheckpointDiagnosticStores(fromSecs, it) }
                            checkService()
                        } else if (!dashjEngineMayStart && peerGroup != null) {
                            // Re-hold: stop the diagnostic dashj engine.
                            stopPeerGroup()
                        }
                    } catch (e: Exception) {
                        log.error("onDashjDiagnosticChanged failed", e)
                    }
                }
            }
        }
    }

    /**
     * SDK L1 wallet-events observed since the last idle-detector tick — the
     * post-cutover analogue of [transactionsReceived] (which only the dashj
     * wallet listener ever increments). Fed by the tap started in
     * [onCreate]; read-and-cleared by [tickReceiver].
     */
    private val sdkTxEventsReceived = AtomicInteger(0)

    private var tickRecieverRegistered = false
    private val tickReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        private var lastChainHeight = 0
        private var lastHeaderHeight = 0
        private var lastSdkProgress: de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress? = null
        private var sdkSampled = false
        private val activityHistory = arrayListOf<SyncActivitySample>()

        override fun onReceive(context: Context, intent: Intent) {
            // WHICH ENGINE'S ACTIVITY COUNTS. The detector exists to stop a
            // genuinely idle service, and its four counters were all dashj-fed.
            // Post-cutover the dashj peergroup is HELD, so every one of them is
            // permanently 0 and the detector trips ~2 minutes after EVERY start,
            // killing the service mid-SDK-scan. Sample the engine that actually
            // owns L1 instead — same idle rule, honest inputs (see
            // SyncActivityIdleDetector.kt). Pre-cutover [dashjHeldByCutover] is
            // false and this is byte-for-byte the original dashj path.
            val sample = if (dashjHeldByCutover) sdkSample() else dashjSample()
            if (sample != null) {
                pushAndEvaluate(sample)
            }
        }

        /** The original dashj counters; null until a first reference height exists. */
        private fun dashjSample(): SyncActivitySample? {
            val chainHeight = blockChain?.bestChainHeight ?: 0
            val headerHeight = headerChain?.bestChainHeight ?: 0
            val sample = if (lastChainHeight > 0 || lastHeaderHeight > 0) {
                SyncActivitySample(
                    transactionsReceived = transactionsReceived.getAndSet(0),
                    blocksDownloaded = chainHeight - lastChainHeight,
                    // instead of counting headers, count header messages which contain up to 2000 headers
                    headersDownloaded = headerHeight - lastHeaderHeight,
                    mnListDiffsDownloaded = mnListDiffsReceived.getAndSet(0)
                )
            } else {
                null
            }
            lastChainHeight = chainHeight
            lastHeaderHeight = headerHeight
            return sample
        }

        /** The SDK L1 engine's equivalent counters (see [sdkActivitySample]). */
        private fun sdkSample(): SyncActivitySample? {
            val current = l1ShadowSyncService.progress.value
            val txEvents = sdkTxEventsReceived.getAndSet(0)
            val sample = if (sdkSampled) {
                sdkActivitySample(lastSdkProgress, current, txEvents)
            } else {
                null // first tick: no reference snapshot yet (mirrors dashj above)
            }
            lastSdkProgress = current
            sdkSampled = true
            return sample
        }

        private fun pushAndEvaluate(sample: SyncActivitySample) {
            // push history (newest first)
            activityHistory.add(0, sample)

            // trim
            while (activityHistory.size > MAX_HISTORY_SIZE) {
                activityHistory.removeAt(activityHistory.size - 1)
            }

            log.info(
                "History of transactions/blocks/headers/mnlistdiff ({}): {}",
                if (dashjHeldByCutover) "sdk" else "dashj",
                activityHistory.joinToString(", ")
            )

            // if idling, shutdown service
            if (isSyncIdle(activityHistory)) {
                log.info("idling detected, stopping service")
                if (blockchainState?.replaying == true) {
                    rescheduleService()
                }
                stopSelf()
            }
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

    @SuppressLint("WrongConstant")
    override fun onCreate() {
        serviceCreatedAt = System.currentTimeMillis()
        log.info(".onCreate()")
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val lockName = "$packageName blockchain sync"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName)

        // Register for app lifecycle events to detect background/foreground transitions
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        // DIAGNOSTIC (Tools toggle): while the readout is holding at 99%
        // waiting for a fresh post-catchup parity report (see
        // publishDashjDiagnostic's freshness gate), progress callbacks alone
        // can't flip it — once caught up they only fire on new blocks (minutes
        // apart). Re-publish whenever the shadow harness produces a report so
        // the 100% + verdict lands within one probe interval (~10s).
        serviceScope.launch {
            l1ShadowSyncService.latestParity.collect { report ->
                if (report != null && dashjSyncDiagnostic && dashjCaughtUpAtMs != null) {
                    publishDashjDiagnostic(lastDiagnosticRawPercent, lastDiagnosticStage)
                }
            }
        }

        // Idle detector, SDK side: the post-cutover analogue of the dashj
        // wallet's transactionsReceived counter (see SyncActivityIdleDetector).
        // Counting only — the tick receiver reads and clears it.
        serviceScope.launch {
            l1ShadowSyncService.txEvents.collect { sdkTxEventsReceived.incrementAndGet() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundAndCatch(createNetworkSyncNotification())

            // TEST ONLY: Simulate onTimeout callback for testing
            if (TEST_TIMEOUT_MINUTES > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceScope.launch {
                    delay(TimeUnit.MINUTES.toMillis(TEST_TIMEOUT_MINUTES))
                    log.warn("TEST: Triggering onTimeout() after {} minutes", TEST_TIMEOUT_MINUTES)
                    onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            }
        }
        serviceScope.launch {
            try {
                log.info("onCreate() serviceScope waiting for cleanup {}", cleanupDeferred?.isActive)

                val cleanupCompleted = withTimeoutOrNull(15_000) {
                    cleanupDeferred?.await()
                    true
                } != null

                if (!cleanupCompleted) {
                    log.error("CRITICAL: Cleanup did not complete within 15 seconds")
                    log.error("This indicates a deadlock in onDestroy - cannot proceed with onCreate")
                    log.error("Stopping service to prevent resource conflicts and file lock exceptions")

                    // Complete onCreate to unblock any waiting onStartCommand calls
                    if (onCreateCompleted.isActive) {
                        onCreateCompleted.complete(Unit)
                    }

                    // Stop the service - we cannot safely initialize with cleanup still running
                    withContext(Dispatchers.Main) {
                        stopSelf()
                    }
                    return@launch
                }

                propagateContext()
                val wallet = application.wallet
                if (wallet == null) {
                    log.error("onCreate: wallet is null after cleanup, service cannot continue")
                    return@launch
                }
                // Phase 5d: resolve the cutover engine gate ONCE, before we
                // release onCreateCompleted — checkService() awaits that latch,
                // so the gate is always settled by the time it decides whether
                // to start the peergroup. Failure defaults to true (start dashj).
                //
                // Resolved HERE, at the very top of the init, rather than after
                // the block-store setup below: the missing-blockstore
                // `wallet.reset()` a few lines down must know whether the SDK
                // owns L1 (see mayResetDashjWalletForMissingBlockstore), and the
                // idle detector's tick receiver — registered further down — picks
                // its sample source from the same flags.
                val coordinatorAllowsDashj = runCatching { cutoverCoordinator.dashjEngineMayStart() }
                    .getOrDefault(true)
                // DIAGNOSTIC un-hold (Tools toggle): when the cutover has committed
                // (SDK owns L1) but the tester turned on DASHJ_SYNC_DIAGNOSTIC, let the
                // dashj peergroup start anyway so it syncs as a backup / parity check.
                // This ONLY relaxes the LOCAL engine-start gate — it does NOT touch the
                // cutover state or CutoverCoordinator.sdkOwnsL1Flow(), so sdkOwnsL1 stays
                // true and the home header keeps reading the SDK's L1 sync. Flag off ⇒
                // dashjEngineMayStart == coordinatorAllowsDashj, byte-for-byte as before.
                dashjSyncDiagnostic = runCatching { dashPayConfig.getDashjSyncDiagnostic() }
                    .getOrDefault(false)
                dashjHeldByCutover = !coordinatorAllowsDashj
                dashjEngineMayStart = coordinatorAllowsDashj || dashjSyncDiagnostic
                if (!dashjSyncDiagnostic) dashjDiagnosticSyncState.reset()
                log.info(
                    "Phase 5d cutover gate: dashjEngineMayStart={} (coordinatorAllowsDashj={}, " +
                        "dashjHeldByCutover={}, dashjSyncDiagnostic={})",
                    dashjEngineMayStart, coordinatorAllowsDashj, dashjHeldByCutover, dashjSyncDiagnostic
                )

                peerConnectivityListener = PeerConnectivityListener()
                broadcastPeerState(0)
                blockChainFile =
                    File(getDir("blockstore", MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME)
                val blockChainFileExists = blockChainFile!!.exists()
                headerChainFile = File(getDir("blockstore", MODE_PRIVATE), Constants.Files.HEADERS_FILENAME)
                mnlistinfoBootStrapStream = loadStream(Constants.Files.MNLIST_BOOTSTRAP_FILENAME)
                qrinfoBootStrapStream = loadStream(Constants.Files.QRINFO_BOOTSTRAP_FILENAME)
                if (!blockChainFileExists) {
                    propagateContext()
                    // wallet.reset() exists so dashj can re-download the chain
                    // from scratch. Post-cutover that re-download never happens
                    // (the peergroup is held) while dashj is still the wallet of
                    // record the home-screen history is rebuilt from — so the
                    // reset would permanently destroy history in exchange for
                    // nothing. See mayResetDashjWalletForMissingBlockstore.
                    val dashjTxCount = runCatching { wallet.getTransactionCount(true) }.getOrDefault(0)
                    if (mayResetDashjWalletForMissingBlockstore(dashjHeldByCutover, dashjTxCount)) {
                        log.info("blockchain does not exist, resetting wallet")
                        wallet.reset()
                    } else {
                        log.warn(
                            "blockchain store is missing, but the SDK owns L1 and the dashj wallet " +
                                "still holds {} transactions — SKIPPING wallet.reset(). The held " +
                                "peergroup would never re-download them, and the reset also wipes " +
                                "the transaction display caches, so the history would be " +
                                "unrecoverable",
                            dashjTxCount
                        )
                    }
                    resetMNLists(false)
                    resetMNListsOnPeerGroupStart = true
                }
                try {
                    blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile)
                    blockStore?.chainHead // detect corruptions as early as possible
                    headerStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, headerChainFile)
                    headerStore?.chainHead // detect corruptions as early as possible
                    wallet.isNotifyTxOnNextBlock = false
                    val blockchainStoreMemoryError = serviceConfig.get(BLOCKCHAIN_STORE_MEMORY_FAILURE) ?: false
                    if (blockchainStoreMemoryError) {
                        try {
                            // the stores are open, copy 100 blocks to a new store.
                            log.info("attempting to fix blockchain memory stalls by copying from store: {}", serviceConfig.getBlockStoreLastFix())

                            val blockchainFileTemp = File(getDir("blockstore", MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME + "-temp")
                            val headerFileTemp = File(getDir("blockstore", MODE_PRIVATE), Constants.Files.HEADERS_FILENAME + "-temp")

                            // Clean up any existing temp files from previous failed attempts
                            blockchainFileTemp.delete()
                            headerFileTemp.delete()

                            var blockStoreTemp: SPVBlockStore? = null
                            var headerStoreTemp: SPVBlockStore? = null

                            try {
                                blockStoreTemp = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockchainFileTemp)
                                headerStoreTemp = SPVBlockStore(Constants.NETWORK_PARAMETERS, headerFileTemp)

                                // Copy blockchain blocks
                                var cursor = blockStore!!.chainHead
                                val chainHead = cursor
                                blockStoreTemp.put(cursor)
                                var blockCount = 1
                                for (i in 0 until 500) {
                                    cursor = cursor!!.getPrev(blockStore!!)
                                    if (cursor == null || cursor.header == Constants.NETWORK_PARAMETERS.genesisBlock) {
                                        break
                                    }
                                    blockStoreTemp.put(cursor)
                                    blockCount++
                                }
                                blockStoreTemp.chainHead = chainHead
                                log.info("copied {} blocks to temp store", blockCount)

                                // Copy header blocks
                                cursor = headerStore!!.chainHead
                                val headerChainHead = cursor
                                headerStoreTemp.put(cursor)
                                var headerCount = 1
                                for (i in 0 until 500) {
                                    cursor = cursor!!.getPrev(headerStore!!)
                                    if (cursor == null || cursor.header == Constants.NETWORK_PARAMETERS.genesisBlock) {
                                        break
                                    }
                                    headerStoreTemp.put(cursor)
                                    headerCount++
                                }
                                headerStoreTemp.chainHead = headerChainHead
                                log.info("copied {} headers to temp store", headerCount)

                                // Close temp stores first
                                headerStoreTemp.close()
                                headerStoreTemp = null
                                blockStoreTemp.close()
                                blockStoreTemp = null

                                // Close old stores
                                blockStore!!.close()
                                headerStore!!.close()

                                // Delete old files
                                if (!blockChainFile!!.delete()) {
                                    log.error("Failed to delete old blockchain file")
                                    throw IOException("Failed to delete old blockchain file")
                                }
                                if (!headerChainFile!!.delete()) {
                                    log.error("Failed to delete old header chain file")
                                    throw IOException("Failed to delete old header chain file")
                                }

                                // Rename temp files to actual files
                                if (!blockchainFileTemp.renameTo(blockChainFile!!)) {
                                    log.error("Failed to rename temp blockchain file")
                                    throw IOException("Failed to rename temp blockchain file")
                                }
                                if (!headerFileTemp.renameTo(headerChainFile!!)) {
                                    log.error("Failed to rename temp header file")
                                    throw IOException("Failed to rename temp header file")
                                }

                                log.info("completed file replacement, now reloading the block stores")
                                blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile)
                                blockStore?.chainHead // detect corruptions as early as possible
                                headerStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, headerChainFile)
                                headerStore?.chainHead // detect corruptions as early as possible
                                log.info("block stores reloaded successfully")

                                // Record that we attempted this fix
                                serviceConfig.set(BLOCKCHAIN_STORE_LAST_FIX, BlockStoreLastFix.COPY_FROM_STORE.name)
                                serviceConfig.set(BLOCKCHAIN_STORE_MEMORY_FAILURE, false)
                            } finally {
                                // Ensure temp stores are closed even if an error occurs
                                try {
                                    blockStoreTemp?.close()
                                } catch (e: Exception) {
                                    log.warn("Error closing temp blockStore", e)
                                }
                                try {
                                    headerStoreTemp?.close()
                                } catch (e: Exception) {
                                    log.warn("Error closing temp headerStore", e)
                                }
                            }
                        } catch (e: Exception) {
                            log.error("Failed to fix blockchain memory stalls by copying from store", e)
                            // Clean up temp files on failure
                            try {
                                val blockchainFileTemp = File(getDir("blockstore", MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME + "-temp")
                                val headerFileTemp = File(getDir("blockstore", MODE_PRIVATE), Constants.Files.HEADERS_FILENAME + "-temp")
                                blockchainFileTemp.delete()
                                headerFileTemp.delete()
                            } catch (cleanupEx: Exception) {
                                log.warn("Error cleaning up temp files", cleanupEx)
                            }
                        }
                    }
                    // Runs on serviceScope (Dispatchers.IO) — deliberately NOT hopped to
                    // Dispatchers.Main. verifyBlockStores() blocks the calling thread on
                    // two `future[1, TimeUnit.SECONDS]` gets plus the SPV store reads
                    // behind them, so on Main it is a ~2s+ stall right in the startup
                    // window. Nothing in it touches the UI; the Main hop was an artifact
                    // of the Java→Kotlin conversion (#1336).
                    verifyBlockStores()
//                    if (blockStore is TestingSPVBlockStore) {
//                        (blockStore as TestingSPVBlockStore).setBlockGetMethod(true)
//                    }
                    val earliestKeyCreationTime = serviceConfig.getWalletCreationDate() ?: wallet.earliestKeyCreationTime
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
                    // Check if this is a file lock exception - don't delete files if another service is using them
                    val isFileLockException = x.cause?.javaClass?.simpleName?.contains("OverlappingFileLockException") == true ||
                                            x.message?.contains("OverlappingFileLockException") == true ||
                                            x.message?.contains("FileLock") == true
                    
                    if (isFileLockException) {
                        log.warn("BlockStore creation failed due to file lock conflict - NOT deleting blockchain files as another service may be using them: {}", x.message)
                        val msg = "blockstore cannot be created due to file lock conflict - files preserved"
                        log.error(msg, x)
                        throw Error(msg, x)
                    } else {
                        log.warn("BlockStore creation failed due to corruption or other error - deleting blockchain files for clean restart: {}", x.message)
                        blockChainFile!!.delete()
                        headerChainFile!!.delete()
                        resetMNLists(false)
                        val msg = "blockstore cannot be created"
                        log.error(msg, x)
                        throw Error(msg, x)
                    }
                }
                try {
                    propagateContext()
                    blockChain = BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore)
                    headerChain = BlockChain(Constants.NETWORK_PARAMETERS, headerStore)
                } catch (x: BlockStoreException) {
                    throw Error("blockchain cannot be created", x)
                }
                // register network and storage receivers on the main thread
                withContext(Dispatchers.Main) {
                    // Initialize network callback (now that securityFunctions is available)
                    networkCallback = NetworkCallbackImpl()

                    // Register network callback for modern network monitoring
                    // Use NetworkRequest to reliably detect when no validated network is available (e.g., airplane mode)
                    val networkRequest = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        .build()
                    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
                    networkCallbackRegistered = true
                    log.info("network callback registered with NetworkRequest: {}", networkCallback)
                }
                monitorOlderTransactions(wallet)
                wallet.addCoinsReceivedEventListener(
                    Threading.SAME_THREAD,
                    walletEventListener
                )
                wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener)
                wallet.addChangeEventListener(Threading.SAME_THREAD, walletEventListener)
                blockChain?.addNewBestBlockListener(newBestBlockListener)
                config.registerOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
                withContext(Dispatchers.Main) {
                    registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
                    tickRecieverRegistered = true
                    log.info("receiver register: tickReceiver, {}", tickReceiver)
                }
                peerDiscoveryList.add(dnsDiscovery)
                updateAppWidget()
                blockchainStateDao.observeState().observe(this@BlockchainServiceImpl) { blockchainState ->
                    handleBlockchainStateNotification(blockchainState)
                }
                apiConfirmationHandler = registerCrowdNodeConfirmedAddressFilter()

                application.observeTotalBalance().observe(this@BlockchainServiceImpl) {
                    balance = it
                    updateAppWidget()
                }


                txConfidenceJob = merge(
                    currentBlock.filterNotNull().sample(APPWIDGET_THROTTLE_MS),
                    currentBlock.filterNotNull().debounce(APPWIDGET_THROTTLE_MS)
                ).distinctUntilChanged()
                    .onEach { updateTxConfidence() }
                    .launchIn(serviceScope)

                // DIAGNOSTIC (Tools toggle) "sync from date": before the un-held
                // dashj engine may start, fast-forward the diagnostic blockstore
                // to the tester-chosen start date (see
                // [maybeRecheckpointDiagnosticStores]). Runs BEFORE
                // onCreateCompleted resolves, so checkService() cannot race it.
                // STRICTLY scoped to the post-cutover diagnostic path — the
                // pre-cutover primary dashj engine (dashjHeldByCutover == false)
                // and its store handling are untouched.
                if (dashjSyncDiagnostic && dashjHeldByCutover) {
                    val diagnosticFromSecs = runCatching { dashPayConfig.getDashjSyncDiagnosticFromSecs() }
                        .getOrDefault(0L)
                    maybeRecheckpointDiagnosticStores(diagnosticFromSecs, wallet)
                }

                if (!dashjEngineMayStart && Constants.SUPPORTS_PLATFORM) {
                    // SDK-sourced quorums (Phase 5d follow-up): with the dashj
                    // engine held, checkService() never runs initDashSync() /
                    // setMasternodeListManager(), so the dashj-platform DAPI
                    // client had no quorum source — every proof verification
                    // failed ("quorum not found"), each DAPI address got
                    // banned (NoAvailableAddresses; DashPay dark) and the
                    // quorum callback spammed lateinit crashes (observed live
                    // on the cutover rehearsal). Wire Platform's quorum
                    // lookups to the Kotlin SDK's getCurrentQuorumsInfo-backed
                    // source instead; the bridge manager's empty masternode
                    // list makes DAPI addresses fall back to the network's
                    // default HP masternode list.
                    runCatching {
                        platformRepo.platform.setMasternodeListManager(
                            sdkQuorumDataSource.createMasternodeListManager()
                        )
                    }.onSuccess {
                        log.info("cutover committed — Platform quorum lookups wired to the SDK-sourced quorum list")
                    }.onFailure {
                        log.warn("failed to wire SDK-sourced quorums for Platform", it)
                    }
                }

                // FIX 2: keep the DASHJ_SYNC_DIAGNOSTIC toggle effective on a LIVE
                // service — restartBlockchainService() can't be relied on to recreate a
                // foreground service, so the one-shot gate above would never re-resolve.
                // Observe the flag for the service's lifetime and actually (un)hold the
                // dashj peergroup. The first (current-value) emission is a no-op (the gate
                // was just resolved from it). Inert pre-cutover / flag-off.
                dashPayConfig.observeDashjSyncDiagnostic()
                    .distinctUntilChanged()
                    .onEach { enabled -> (networkCallback as? NetworkCallbackImpl)?.onDashjDiagnosticChanged(enabled) }
                    .launchIn(serviceScope)

                onCreateCompleted.complete(Unit)
                log.info(".onCreate() finished")
            } catch (t: Throwable) {
                log.error("Service initialization failed in background coroutine, stopping service", t)
                if (onCreateCompleted.isActive) {
                    onCreateCompleted.complete(Unit)
                }
                // Service is in a broken state - stop it gracefully
                handler.post {
                    stopSelf()
                }
            }
        }
    }

    /**
     * DIAGNOSTIC-ONLY store fast-forward for the "dashj sync (diagnostic)"
     * Tools toggle. Callers guarantee we are on the post-cutover diagnostic
     * path ([dashjSyncDiagnostic] && [dashjHeldByCutover]) with the peergroup
     * down — the pre-cutover primary dashj engine never reaches this and its
     * store handling stays byte-for-byte unchanged.
     *
     * WHY: a post-cutover restore stamps the dashj wallet's seed with the
     * EARLIEST_HD_SEED_CREATION_TIME sentinel (2015) and rarely persists a
     * real creation date, so onCreate's normal fresh-store checkpointing
     * ([CheckpointManager.checkpoint] to earliestKeyCreationTime) lands near
     * genesis and the diagnostic run syncs the whole chain (~block 1728 of
     * 1.5M observed on testnet). The Tools toggle now asks for a start date
     * (restore-flow style); this applies it with the SAME CheckpointManager
     * mechanism the normal path uses.
     *
     * RESUME vs RECREATE: the fresh start the chosen date would give is the
     * last bundled checkpoint at/before [fromSecs]. If the existing store's
     * head is already at/beyond that height, the store has real progress —
     * leave it and resume. Only when the chosen date demands a FRESHER start
     * (e.g. the store is a from-genesis start at a low height) are the
     * diagnostic stores deleted and recreated checkpointed at the date. This
     * rule is idempotent: after a recreation the head equals the checkpoint,
     * so subsequent calls resume.
     *
     * PARITY VALIDITY: syncing from a date at/before the wallet's creation
     * date still observes every wallet transaction — coins cannot predate the
     * wallet's keys — so the SDK-vs-dashj parity verdict is unaffected; only
     * empty pre-birth blocks are skipped. (The dialog subtext tells the tester
     * to use the wallet's creation date.)
     *
     * The dashj wallet itself is deliberately NOT reset (unlike onCreate's
     * missing-file path): dashj still owns wallet persistence, and wiping its
     * transactions for a diagnostic is unacceptable. A low
     * wallet.lastBlockSeenHeight merely triggers checkService()'s existing
     * "wallet/blockchain out of sync" log line on the diagnostic run.
     */
    private fun maybeRecheckpointDiagnosticStores(fromSecs: Long, wallet: Wallet) {
        if (fromSecs <= 0) return // "Sync everything" — leave the stores alone
        try {
            val store = blockStore ?: return
            val currentHead: StoredBlock? = store.chainHead
            val target: StoredBlock = assets.open(Constants.Files.CHECKPOINTS_FILENAME).use { stream ->
                CheckpointManager(Constants.NETWORK_PARAMETERS, stream).getCheckpointBefore(fromSecs)
            }
            if (currentHead != null && currentHead.height >= target.height) {
                log.info(
                    "dashj-sync-diagnostic: store head {} at/beyond checkpoint {} for from-date {} — resuming",
                    currentHead.height, target.height, fromSecs
                )
                return
            }
            log.info(
                "dashj-sync-diagnostic: store head {} predates checkpoint {} for from-date {} — " +
                    "recreating diagnostic stores checkpointed at the chosen date",
                currentHead?.height, target.height, fromSecs
            )
            // The peergroup is down and (onCreate latch / checkMutex) no
            // checkService() can run concurrently; the only live consumer of
            // the old chain is newBestBlockListener — detach it, swap, re-attach.
            blockChain?.removeNewBestBlockListener(newBestBlockListener)
            blockStore?.close()
            headerStore?.close()
            blockChainFile?.delete()
            headerChainFile?.delete()
            blockStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile)
            headerStore = SPVBlockStore(Constants.NETWORK_PARAMETERS, headerChainFile)
            // Same checkpoint mechanism as onCreate's fresh-store path: block
            // store to the chosen date, header store to the most recent checkpoint.
            var checkpointsInputStream = assets.open(Constants.Files.CHECKPOINTS_FILENAME)
            CheckpointManager.checkpoint(
                Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore, fromSecs
            )
            checkpointsInputStream = assets.open(Constants.Files.CHECKPOINTS_FILENAME)
            CheckpointManager.checkpoint(
                Constants.NETWORK_PARAMETERS, checkpointsInputStream, headerStore,
                System.currentTimeMillis() / 1000
            )
            blockChain = BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore)
            headerChain = BlockChain(Constants.NETWORK_PARAMETERS, headerStore)
            blockChain?.addNewBestBlockListener(newBestBlockListener)
            // Mirror onCreate's fresh-store masternode-list handling: the MN
            // list must re-bootstrap for the jumped-ahead height. checkService()
            // consumes the flag on the next (diagnostic) peergroup start.
            resetMNLists(false)
            resetMNListsOnPeerGroupStart = true
            log.info(
                "dashj-sync-diagnostic: diagnostic stores recreated at checkpoint height {} ({})",
                target.height, target.header.time
            )
        } catch (e: Exception) {
            // Best-effort: a failure here only costs sync time (worst case the
            // diagnostic syncs from its old position), never correctness.
            log.error("dashj-sync-diagnostic: failed to re-checkpoint diagnostic stores; continuing", e)
        }
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
                    // Same lane as notifyCoinsReceived, so reset-vs-notify can
                    // never interleave and the cancel binder call stays off
                    // this coroutine.
                    notificationHandler.post {
                        notificationCount = 0
                        notificationAccumulatedAmount = Coin.ZERO
                        notificationAddresses.clear()
                        nm!!.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED)
                    }
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
                                // tx.confidence.setPeerInfo(0, 1)
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

    @SuppressLint("WrongConstant")
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
                log.info("start foreground service")
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // The app is in the background and is not allowed to promote to the foreground.
                // Don't retry on a delay - a postDelayed() retry can't beat the ~5s startForeground()
                // deadline. Just log and continue running as an ordinary background service; the
                // service is re-promoted later via rescheduleService()/forceForeground() once the app
                // is foregrounded. (Matches upstream bitcoin-wallet BlockchainService behavior.)
                log.warn("foreground start not allowed, continuing as background service", e)
            }
        } else {
            startForeground(notification)
            log.info("start foreground service")
        }
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
        if (networkCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
            availableNetworks.clear()
        }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)

        log.info("receivers unregistered, Now starting coroutine to finish the rest of the cleanup")

        // Monitor the entire cleanup process with timeout reporting
        val cleanupThread = Thread.currentThread()
        val cleanupMonitorJob = serviceScope.launch {
            delay(5_000) // Wait 5 seconds before first check
            var checkCount = 0
            while (isActive) {
                if (checkCount > 4) return@launch
                checkCount++
                log.warn("onDestroy() cleanup is taking longer than {} seconds", 5 * checkCount)
                try {
                    val anrException = AnrException(cleanupThread)
                    anrException.logProcessMap()
                } catch (e: Exception) {
                    log.error("Failed to dump thread traces during cleanup timeout", e)
                }
                // Wait another 5 seconds before next check
                delay(5_000)
            }
        }

        serviceScope.launch {
            try {
                log.info("The onCreateCompleted is active: {}", onCreateCompleted.isActive)
                onCreateCompleted.await() // wait until onCreate is finished
                log.info("The check() mutex is locked: {}", checkMutex.isLocked)
                // Prevent multiple cleanup operations using atomic flag
                if (!isCleaningUp.compareAndSet(false, true)) {
                    log.info("Another onDestroy() is already running cleanup, skipping duplicate cleanup")
                    return@launch
                }

                // Create cleanup coordination only if none exists or existing one is already completed
                val existingCleanup = cleanupDeferred
                if (existingCleanup == null || existingCleanup.isCompleted) {
                    cleanupDeferred = CompletableDeferred()
                    log.info("Created new cleanupDeferred for coordination (previous was {})",
                        if (existingCleanup == null) "null" else "completed")
                } else {
                    log.info("Using existing active cleanupDeferred for coordination")
                }

                // Try to acquire mutex with timeout
                val mutexAcquired = withTimeoutOrNull(5_000) {
                    checkMutex.lock()
                    true
                } != null

                if (!mutexAcquired) {
                    log.error("CRITICAL: Failed to acquire check() mutex within 5 seconds")
                    log.error("A check() operation appears to be deadlocked - proceeding with cleanup anyway")
                    log.error("This may result in resource leaks but allows service restart")
                    // Dump all thread stack traces using AnrException
                    try {
                        val anrException = de.schildbach.wallet.util.AnrException(Thread.currentThread())
                        anrException.logProcessMap()
                    } catch (e: Exception) {
                        log.error("Failed to dump thread traces", e)
                    }

                }

                log.info("detaching from wallet")
                WalletApplication.scheduleStartBlockchainService(this@BlockchainServiceImpl) //disconnect feature
                blockChain?.removeNewBestBlockListener(newBestBlockListener)
                // Cancel tx confidence monitoring job before cleanup to prevent CME
                txConfidenceJob?.cancel()
                txConfidenceJob?.join()
                txConfidenceJob = null

                val wallet = application.wallet
                if (wallet != null) {
                    wallet.removeChangeEventListener(walletEventListener)
                    wallet.removeCoinsSentEventListener(walletEventListener)
                    wallet.removeCoinsReceivedEventListener(walletEventListener)

                    stopMonitoringOlderTransactions(wallet)
                }
                config.unregisterOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
                platformSyncService.shutdown()
                // Snapshot peerGroup into a local. onDestroy() may proceed without the check()
                // mutex (after the 5s timeout above), so a concurrent checkService() can set the
                // peerGroup field to null between this guard and any later deref. Using the local
                // avoids that check-then-act race (previously an NPE at peerGroup!!.forceStop()).
                val localPeerGroup = peerGroup
                if (localPeerGroup != null) {
                    log.info("shutting down peerGroup and system services")
                    propagateContext()
                    // we may need to skip these, or move them to after the forceStop because they grab a lock
                    if (!localPeerGroup.lock.isLocked) {
                        localPeerGroup.removeDisconnectedEventListener(peerConnectivityListener)
                        localPeerGroup.removeConnectedEventListener(peerConnectivityListener)
                        localPeerGroup.removeTimeoutErrorListener(timeoutErrorListener)
                    }
                    dashSystemService.system.removeWallet(wallet)
                    localPeerGroup.removeWallet(application.wallet)
                    platformSyncService.removePreBlockProgressListener(blockchainDownloadListener)
                    log.info("CLEANUP STEP 1: peerGroup listeners and wallet removed")
                    blockchainStateDataProvider.setNetworkStatus(NetworkStatus.DISCONNECTING)
                    log.info("CLEANUP STEP 2: About to call peerGroup.forceStop(7000)")
                    localPeerGroup.forceStop(7_000)
                    log.info("CLEANUP STEP 2: peerGroup.forceStop() completed")
                    blockchainStateDataProvider.setNetworkStatus(NetworkStatus.STOPPED)
                    log.info("CLEANUP STEP 3: About to close dashSystemService.system")
                    dashSystemService.system.close()
                    log.info("CLEANUP STEP 3: Dash system services are shutdown")
                    if (wallet != null) {
                        wallet.riskAnalyzer = defaultRiskAnalyzer
                    }
                    log.info("CLEANUP STEP 4: About to shutdown riskAnalyzer")
                    riskAnalyzer?.shutdown()
                    log.info("CLEANUP STEP 4: riskAnalyzer shutdown completed, peergroup fully stopped")
                }
                log.info("CLEANUP STEP 5: About to stop peerConnectivityListener")
                peerConnectivityListener?.stop()
                log.info("CLEANUP STEP 5: peerConnectivityListener stopped")
                delayHandler.removeCallbacksAndMessages(null)
                // Drain-and-quit the notification lane: processes anything
                // already posted (including the listener's final cancel above),
                // then lets the thread exit.
                notificationHandlerThread.quitSafely()
                log.info("CLEANUP STEP 6: delayHandler callbacks cleared")
                try {
                    log.info("closing blockchain stores")
                    blockStore?.close()
                    headerStore?.close()
                } catch (x: BlockStoreException) {
                    throw RuntimeException(x)
                }
                if (!deleteWalletFileOnShutdown) {
                    propagateContext()
                    application.saveWallet()
                }
                // wakeLock is only assigned in onCreate; if onDestroy runs after an early/partial
                // onCreate it may still be null, so guard rather than assert.
                wakeLock?.let { lock ->
                    if (lock.isHeld) {
                        log.debug("wakelock still held, releasing")
                        lock.release()
                    }
                }
                if (resetBlockchainOnShutdown || deleteWalletFileOnShutdown) {
                    log.info("removing blockchain")
                    blockChainFile?.delete()
                    headerChainFile?.delete()
                    resetMNLists(false)
                    if (deleteWalletFileOnShutdown) {
                        log.info("removing wallet file and app data")
                        // The Kotlin-SDK engines must be provably down before the wipe
                        // deletes app data. DEBUG builds deliberately skip the engine
                        // stops in platformSyncService.shutdown() above (warm-SPV
                        // battery trade-off for testing) — this explicit stop keeps the
                        // wipe path safe on every build type (no-op when already
                        // stopped, as on release where shutdown() stopped them).
                        platformSyncService.stopSdkEngines()
                        application.finalizeWipe()
                    }
                    //Clear the blockchain identity
                    application.clearDatabases(false)
                    resetBlockchainState()
                }
                log.info("closing bootstrap streams")
                closeStream(mnlistinfoBootStrapStream)
                closeStream(qrinfoBootStrapStream)
            } finally {
                log.info("serviceJob cancelled after " + (System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60 + " minutes")
                serviceJob.cancel()
                if (checkMutex.isLocked) {
                    checkMutex.unlock()
                }
                isCleaningUp.set(false)
                cleanupDeferred?.complete(Unit)
                // Cancel the cleanup monitor since cleanup is done
                cleanupMonitorJob.cancel()
            }
        }
        log.info("service was up for " + (System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60 + " minutes")
    }

    private fun resetBlockchainState() {
        syncPercentage = 0
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
        log.info("onTimeoutCalled({}, {})", startId, fgsType)
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

    @SuppressLint("WrongConstant")
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

//    /**
//     * Check impediments and start/stop peergroup accordingly
//     * Can be called from network callbacks or security health observers
//     */
//    private fun checkImpediments() {
//        serviceScope.launch {
//            try {
//                // make sure that onCreate is finished
//                onCreateCompleted.await()
//                log.info("acquiring check() mutex")
//
//                // Use withLock to guarantee mutex release even if coroutine is cancelled
//                checkMutex.withLock {
//                    try {
//                        checkService()
//                    } catch (e: Exception) {
//                        log.error("checkService() failed with exception", e)
//                        // Don't rethrow - we want to ensure clean mutex release
//                    }
//                }
//                log.info("releasing check() mutex")
//            } catch (e: Exception) {
//                log.error("check() failed", e)
//            }
//        }
//    }

    private fun updateBlockchainState() {
        val percent = percentageSync()
        val stage = if (peerGroup != null) peerGroup!!.syncStage else null

        // DIAGNOSTIC (Tools toggle): when the cutover has committed (SDK owns L1)
        // AND the diagnostic un-held dashj, dashj is running purely as a backup /
        // parity check — it must NOT overwrite the shared blockchain_state row
        // (percentageSync/syncStage/impediments) the SDK now drives (Option B).
        // Route its progress to the isolated diagnostic holder instead and return.
        if (dashjSyncDiagnostic && dashjHeldByCutover) {
            publishDashjDiagnostic(percent, stage)
            return
        }

        // Pre-cutover with the diagnostic on: dashj IS still the primary L1
        // engine, so keep writing the shared row exactly as before AND mirror
        // its progress into the diagnostic readout (additive).
        if (dashjSyncDiagnostic) {
            publishDashjDiagnostic(percent, stage)
        }

        // Flag off (or pre-cutover flag off): byte-for-byte the original call.
        blockchainStateDataProvider.updateBlockchainState(
            blockChain!!, impediments, percent, stage
        )
    }

    /**
     * Publish one dashj progress sample to the isolated diagnostic holder
     * ([dashjDiagnosticSyncState]). Once dashj has caught up (100%) read the
     * latest SDK-vs-dashj parity verdict from the L1 shadow-sync harness
     * ([L1ShadowSyncService.latestParity]) and colour the readout
     * MATCH/BALANCE_MATCH/MISMATCH; while syncing the verdict is UNKNOWN.
     *
     * FRESHNESS GATE (owner's spec): 100% and the verdict colour must land
     * TOGETHER, and only once the parity check has actually run against the
     * caught-up dashj state. A report captured while dashj was still
     * downloading (or from a previous run — [L1ShadowSyncService.latestParity]
     * is a sticky StateFlow) proves nothing about the final state, so until a
     * report whose [ParityReport.timestampMs] is at/after the moment dashj
     * reached 100 ([dashjCaughtUpAtMs]) exists, the snapshot is published with
     * `verifying = true` (internal percent held at 99, verdict UNKNOWN) and
     * the Tools readout shows a neutral "Verifying" instead of a percentage.
     * Logs a single SDK-vs-dashj line each time the verdict changes —
     * necessarily at/after that same moment.
     */
    private fun publishDashjDiagnostic(rawPercent: Int, stage: PeerGroup.SyncStage?) {
        lastDiagnosticRawPercent = rawPercent
        lastDiagnosticStage = stage
        // The shared-row percentage is TIME-weighted (block timestamps vs the
        // clock), which wobbles non-monotonically on testnet's irregular block
        // production and under-reads badly (e.g. 52% shown at ~75% of blocks
        // downloaded). For the DIAGNOSTIC readout use a HEIGHT-based percent
        // during block download — downloaded height over the peers' most
        // common tip height — which is monotonic and matches reality. Fall
        // back to the time-based figure outside the BLOCKS stage (headers /
        // masternode-list phases have no meaningful block-height ratio) or
        // when heights aren't available. Diagnostic-only: the shared
        // blockchain_state row and the pre-cutover UI are untouched.
        val percent = run {
            val tip = try { peerGroup?.mostCommonChainHeight ?: 0 } catch (_: Exception) { 0 }
            val height = try { blockChain?.bestChainHeight ?: 0 } catch (_: Exception) { 0 }
            if (stage == PeerGroup.SyncStage.BLOCKS && tip > 0 && height in 1..tip) {
                ((height.toLong() * 100L) / tip.toLong()).toInt()
            } else {
                rawPercent
            }
        }
        // Stamp the moment dashj first reaches 100 this stretch; forget it if
        // the chain falls behind again (new blocks arrived) so the next
        // catch-up demands a fresh report of its own.
        if (percent >= 100) {
            if (dashjCaughtUpAtMs == null) {
                dashjCaughtUpAtMs = System.currentTimeMillis()
                log.info(
                    "dashj-sync-diagnostic: dashj chain caught up — showing 'Verifying' " +
                        "until a parity report computed against the caught-up state arrives"
                )
            }
        } else {
            dashjCaughtUpAtMs = null
        }
        val report = l1ShadowSyncService.latestParity.value
        // Fresh = captured at/after the catch-up moment (same wall clock).
        val caughtUpAtMs = dashjCaughtUpAtMs
        val freshReport = report?.takeIf { caughtUpAtMs != null && it.timestampMs >= caughtUpAtMs }
        val parity = if (percent >= 100 && freshReport != null) {
            when {
                freshReport.fullMatch -> DashjDiagnosticSyncState.Parity.MATCH
                // Both balance comparisons agree exactly, only the tx counts
                // differ — the funds are all accounted for on both sides.
                freshReport.balancesMatch && freshReport.confirmedBalancesMatch ->
                    DashjDiagnosticSyncState.Parity.BALANCE_MATCH
                else -> DashjDiagnosticSyncState.Parity.MISMATCH
            }.also { verdict ->
                if (verdict != lastDiagnosticParity) {
                    log.info(
                        "dashj-sync-diagnostic: dashj caught up (100%) — parity {} " +
                            "estimated sdk={} dashj={} confirmed sdk={} dashj={} tx sdk={} dashj={}",
                        verdict, freshReport.sdkDuffs, freshReport.dashjDuffs,
                        freshReport.sdkConfirmedDuffs, freshReport.dashjAvailableDuffs,
                        freshReport.sdkTxCount, freshReport.dashjTxCount
                    )
                }
            }
        } else {
            DashjDiagnosticSyncState.Parity.UNKNOWN
        }
        lastDiagnosticParity = parity
        // dashj itself is done but the fresh post-catchup report hasn't landed
        // yet: hold the internal percent at 99 and flag the window so the
        // readout shows "Verifying" — 100 and the verdict colour then appear
        // together when the report arrives.
        val verifying = percent >= 100 && parity == DashjDiagnosticSyncState.Parity.UNKNOWN
        val displayPercent = if (verifying) 99 else percent
        dashjDiagnosticSyncState.update(displayPercent, parity, stage?.name, verifying)
        // Feed the support-log history buffer (deduplicated inside).
        dashjDiagnosticSyncState.recordParity(displayPercent, parity, report)
    }

    override fun getConnectedPeers(): List<Peer> {
        return if (peerGroup != null) peerGroup!!.connectedPeers else emptyList()
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

    private fun handleBlockchainStateNotification(blockchainState: BlockchainState?) {
        // send this out for the Network Monitor, other activities observe the database
        val broadcast = Intent(BlockchainService.ACTION_BLOCKCHAIN_STATE)
        broadcast.setPackage(packageName)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && blockchainState != null && blockchainState.bestChainDate != null) {
            //Handle Ongoing notification state
            val syncing =
                blockchainState.bestChainDate!!.time < Utils.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS //1 hour
            // NotificationManager calls are synchronous binder IPC and this
            // observer runs on MAIN for every blockchain-state DB write — same
            // blocked-binder ANR shape as the peer-connectivity listener. Ship
            // the notification work to the notification lane.
            notificationHandler.post {
                try {
                    if (!syncing && blockchainState.bestChainHeight == config.bestChainHeightEver) {
                        //Remove ongoing notification if blockchain sync finished
                        stopForeground(true)
                        foregroundService = ForegroundService.NONE
                        nm!!.cancel(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC)
                    } else if (blockchainState.replaying || syncing) {
                        //Shows ongoing notification when synchronizing the blockchain
                        val notification = createNetworkSyncNotification(blockchainState)
                        nm!!.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification)
                    }
                } catch (e: RuntimeException) {
                    log.warn("notification manager call failed, system may be shutting down", e)
                }
            }
        }
        this.blockchainState = blockchainState
        maybeRecoverIdentityPostCutover(blockchainState)
    }

    /**
     * FIX 1: once the cutover is committed the dashj peerGroup never starts, so its
     * PreBlocksDownloadListener never fires [PlatformSyncService.preBlockDownload] —
     * the trigger that, on a wallet RESTORE, discovered the DashPay identity from the
     * restored keys and enqueued the identity/username/contacts recovery. Re-arm that
     * trigger here: when the SDK-sourced L1 scan first reports synced (the
     * blockchain_state row is SDK-written post-cutover), run the same discovery once.
     *
     * Inert pre-cutover ([dashjHeldByCutover] == false): the dashj preBlockDownload
     * path still owns discovery, so this never double-runs it. Idempotent: fires at
     * most once via [postCutoverIdentityRecoveryTriggered], reset only on failure so a
     * later synced notification can retry.
     */
    private fun maybeRecoverIdentityPostCutover(blockchainState: BlockchainState?) {
        if (!dashjHeldByCutover) return // pre-cutover: the dashj peerGroup path owns discovery
        if (!Constants.SUPPORTS_PLATFORM) return
        if (blockchainState?.isSynced() != true) return
        if (!postCutoverIdentityRecoveryTriggered.compareAndSet(false, true)) return

        log.info("cutover committed and SDK L1 scan synced — triggering identity/contacts discovery")
        serviceScope.launch {
            try {
                platformSyncService.discoverAndRecoverIdentity()
            } catch (e: Exception) {
                log.error("post-cutover identity discovery/recovery failed", e)
                postCutoverIdentityRecoveryTriggered.set(false) // allow a later synced tick to retry
            }
        }
    }

    private fun percentageSync(): Int {
        return syncPercentage
    }

    private var handleContactPaymentsJob: Job? = null
    private val pendingContactPaymentTxs = mutableListOf<Transaction>()

    private fun handleContactPayments(tx: Transaction) {
        if (blockchainState?.replaying != true) {
            synchronized(pendingContactPaymentTxs) {
                pendingContactPaymentTxs.add(tx)
            }
            handleContactPaymentsJob?.cancel()
            handleContactPaymentsJob = serviceScope.launch {
                delay(TimeUnit.SECONDS.toMillis(1)) // debounce delay, 1 second
                val txsToProcess = synchronized(pendingContactPaymentTxs) {
                    pendingContactPaymentTxs.toList().also { pendingContactPaymentTxs.clear() }
                }
                txsToProcess.forEach { identityRepo.updateFrequentContacts(it) }
            }
        }
    }

    private fun updateAppWidget() {
        // val balance = application.getWalletBalance()
        WalletBalanceWidgetProvider.updateWidgets(this@BlockchainServiceImpl, balance)
    }

    fun forceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(this, BlockchainServiceImpl::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    ContextCompat.startForegroundService(this, intent)
                    // call startForeground just after startForegroundService.
                    startForegroundAndCatch(createNetworkSyncNotification())
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    // Not allowed to start an FGS from the background. The service is never created,
                    // so no startForeground() promise is armed - nothing to clean up, just log.
                    // (Matches upstream bitcoin-wallet BlockchainService.start().)
                    log.info("failed to start in foreground", e)
                }
            } else {
                ContextCompat.startForegroundService(this, intent)
                // call startForeground just after startForegroundService.
                startForegroundAndCatch(createNetworkSyncNotification())
            }
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

     //TODO: should we have a backup blockchain file?
    private val newBestBlockListener: NewBestBlockListener = NewBestBlockListener {
        currentBlock.value = it
    }

    @Throws(BlockStoreException::class)
    private fun verifyBlockStore(store: BlockStore?): Boolean {
        val watch = Stopwatch.createStarted()
        var cursor = store!!.chainHead
        for (i in 0 until 100) {
            cursor = cursor!!.getPrev(store)
            if (cursor == null || cursor.header == Constants.NETWORK_PARAMETERS.genesisBlock) {
                break
            }
        }
        log.info("verify blockstore: {}, {} loaded", watch, cursor?.let { store.chainHead.height - it.height })
        return true
    }

    private fun verifyBlockStore(
        store: BlockStore?,
        scheduledExecutorService: ScheduledExecutorService
    ): Boolean {
        return try {
            val future = scheduledExecutorService.schedule<Boolean>(
                { verifyBlockStore(store) },
                100,
                TimeUnit.MILLISECONDS
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
        log.info("blockstore files verified: {}, {}", verifiedHeaderStore, verifiedBlockStore)
    }
}
