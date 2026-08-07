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

package de.schildbach.wallet.ui.main

import android.os.LocaleList
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.DeviceInfoProvider
import de.schildbach.wallet.service.platform.ContactRequestNotificationService
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.TxDisplayCacheService
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService
import de.schildbach.wallet.service.L1SyncStatusService
import de.schildbach.wallet.service.L1SyncUiStatus
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.ui.dashpay.BaseContactsViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet.util.getTimeSkew
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.money.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.CurrencyInfo
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.SyncStage
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.RateRetrievalState
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.integrations.maya.api.DispatchingSwapProvider
import org.dash.wallet.integrations.maya.utils.SwapBackend
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.slf4j.LoggerFactory
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val config: Configuration,
    private val walletUIConfig: WalletUIConfig,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletData,
    private val walletApplication: WalletApplication,
    private val identityRepository: IdentityRepository,
    val platformRepo: PlatformRepo,
    private val platformService: PlatformService,
    private val platformSyncService: PlatformSyncService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val savedStateHandle: SavedStateHandle,
    blockchainStateProvider: BlockchainStateProvider,
    val biometricHelper: BiometricHelper,
    private val deviceInfo: DeviceInfoProvider,
    private val invitationsDao: InvitationsDao,
    private val usernameRequestDao: UsernameRequestDao,
    userAlertDao: UserAlertDao,
    dashPayProfileDao: DashPayProfileDao,
    private val dashPayConfig: DashPayConfig,
    dashPayContactRequestDao: DashPayContactRequestDao,
    private val txDisplayCacheService: TxDisplayCacheService,
    private val crowdNodeApi: CrowdNodeApi,
    private val coinJoinFundsMigrationService: CoinJoinFundsMigrationService,
    l1SyncStatusService: L1SyncStatusService,
    private val contactRequestNotificationService: ContactRequestNotificationService,
    private val swapProvider: DispatchingSwapProvider
) : BaseContactsViewModel(blockchainIdentityDataDao, dashPayProfileDao, dashPayContactRequestDao) {
    var restoringBackup: Boolean = false

    /**
     * The L1 chain-sync state every sync-aware home screen renders —
     * "is it synced", "what percent", "did it fail" — with NO indication of
     * which engine produced it ([L1SyncStatusService] makes that choice at
     * the seam). Replaces the old `sdkOwnsL1` UI flag and the two parallel
     * SDK/dashj feed pairs the Fragments used to select between.
     */
    val syncStatus: StateFlow<L1SyncUiStatus> =
        l1SyncStatusService.status
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), L1SyncUiStatus())

    val balanceDashFormat: MonetaryFormat = config.format.noCode().minDecimals(0)
    val fiatFormat: MonetaryFormat = Constants.LOCAL_FORMAT.minDecimals(0).optionalDecimals(0, 2)

    // Transaction list state delegated to TxDisplayCacheService
    val transactionsLoaded: StateFlow<Boolean> get() = txDisplayCacheService.transactionsLoaded
    val isBuildingCache: StateFlow<Boolean> get() = txDisplayCacheService.isBuildingCache
    val cachedRows: StateFlow<List<HistoryRowView>> get() = txDisplayCacheService.cachedRows
    val transactions: Flow<PagingData<HistoryRowView>> get() = txDisplayCacheService.transactions

    private val _transactionsDirection = MutableStateFlow(TxFilterType.ALL)
    var transactionsDirection: TxFilterType
        get() = _transactionsDirection.value
        set(value) {
            _transactionsDirection.value = value
            savedStateHandle[DIRECTION_KEY] = value
            txDisplayCacheService.setFilter(value)
        }

    private val _isBlockchainSynced = MutableLiveData<Boolean>()
    val isBlockchainSynced: LiveData<Boolean>
        get() = _isBlockchainSynced

    private val _isBlockchainSyncFailed = MutableLiveData<Boolean>()
    val isBlockchainSyncFailed: LiveData<Boolean>
        get() = _isBlockchainSyncFailed

    private val _blockchainSyncPercentage = MutableLiveData<Int>()
    val blockchainSyncPercentage: LiveData<Int>
        get() = _blockchainSyncPercentage
    // Diagnostic bookkeeping for the blockchain-state collector below; both are
    // overwritten by its first emission and are never read before that, so they seed
    // at 0. They deliberately do NOT seed from `walletData.wallet.lastBlockSeenHeight`
    // any more: MainViewModel is constructed from MainActivity.onCreate (on the main
    // thread, while the lock screen is drawing), and that accessor takes dashj's fair
    // wallet read lock — which on a large wallet mid-sync can be held by the autosave
    // serializer for seconds. See the ANR note on the collector.
    private var chainHeight: Int = 0
    private var headersHeight: Int = 0
    private val _syncStage = MutableStateFlow(SyncStage.OFFLINE)
    val syncStage: StateFlow<SyncStage>
        get() = _syncStage

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _rateStale = MutableStateFlow(RateRetrievalState.DEFAULT)
    val rateStale: Flow<RateRetrievalState>
        get() = _rateStale
    val currentStaleRateState
        get() = _rateStale.value
    var rateStaleDismissed = false

    private val _totalBalance = MutableLiveData<Coin>()
    val totalBalance: LiveData<Coin>
        get() = _totalBalance

    private val _temporaryHideBalance = MutableStateFlow<Boolean?>(null)
    val hideBalance = walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE)
        .combine(_temporaryHideBalance) { autoHide, temporaryHide ->
            temporaryHide ?: autoHide ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    private val _remindMetadata = MutableStateFlow(false)
    val remindMetadata = _remindMetadata.asStateFlow()
    val showTapToHideHint = walletUIConfig.observe(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT).asLiveData()

    private val _isNetworkUnavailable = MutableLiveData<Boolean>()
    val isNetworkUnavailable: LiveData<Boolean>
        get() = _isNetworkUnavailable

    val currencyChangeDetected = SingleLiveEvent<Pair<String, String>>()

    // DashPay
    private val isPlatformAvailable = MutableStateFlow(false)

    val isAbleToCreateIdentityLiveData = MediatorLiveData<Boolean>().apply {
        addSource(isPlatformAvailable.asLiveData()) {
            value = combineLatestData()
        }
//        addSource(_isBlockchainSynced) {
//            value = combineLatestData()
//        }
        addSource(blockchainIdentity) {
            value = combineLatestData()
        }
//        addSource(_totalBalance) {
//            value = combineLatestData()
//        }
    }

    val isAbleToCreateIdentity: StateFlow<Boolean> = combine(
        isPlatformAvailable,
        blockchainIdentity.asFlow()
    ) { isPlatformAvailable, identity ->
        combineLatestData()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val showCreateUsernameEvent = SingleLiveEvent<Unit>()

    // One-time-per-launch nudge to withdraw a remaining CrowdNode balance after sync.
    val showCrowdNodeWithdrawalReminder = SingleLiveEvent<Unit>()

    /**
     * Post-upgrade MIXED-FUNDS prompt: this wallet still holds coins on the
     * CoinJoin keychain that the app can no longer spend, and the user has
     * not yet chosen what to do with them. Fires only after the L1 view is
     * current enough to trust the balance — see
     * [CoinJoinFundsMigrationService.shouldPrompt] — and re-fires after
     * activity/ViewModel recreation or lock-unlock until a choice is made
     * (see [recheckMixedFundsMigrationPrompt]).
     */
    /**
     * TEMPORARY KILL SWITCH for the mixed-funds prompt.
     *
     * Both choices the sheet offers route through
     * [CoinJoinFundsMigrationService.combineIntoUnmixedBalance], which takes its
     * destination from dashj's `freshReceiveAddress()` — and that forces a
     * SYNCHRONOUS full-wallet save on the calling thread. On the main thread,
     * on a wallet whose save re-serializes every DashPay friend key chain, that
     * is a multi-second freeze (~7 s measured on a 215-chain mainnet wallet,
     * which the tester read as a hang and force-quit).
     *
     * Suppressing the PROMPT touches neither the migration nor its persisted
     * state: the in-flight and done markers keep their meaning, so flipping
     * this back to false restores the previous behaviour exactly. Remove once
     * the address allocation is off the main thread.
     */
    private val mixedFundsPromptSuppressed = true

    val showMixedFundsMigration = SingleLiveEvent<Unit>()

    /**
     * In-memory throttle for [showMixedFundsMigration]: stops the
     * blockchain-state flow re-firing while the sheet is (presumably) up.
     * Deliberately NOT persisted in [savedStateHandle] — a recreated
     * activity/ViewModel must re-offer the forced choice until the permanent
     * latch ([DashPayConfig.MIXED_FUNDS_MIGRATION_DONE]) is set by an actual
     * migration.
     */
    private var mixedFundsPromptShownThisSession = false

    /**
     * One-time post-UPGRADE sync explainer
     * ([de.schildbach.wallet.ui.cutover.CutoverSyncNoticeDialogFragment]).
     * Fires while the persisted marker
     * ([DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING], armed only on the
     * upgrade seam) is set; the sheet clears the marker when the user
     * acknowledges it, which is what makes it once-ever.
     */
    val showCutoverUpgradeNotice = SingleLiveEvent<Unit>()

    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)
    val seriousErrorLiveData = SeriousErrorLiveData(platformRepo)
    var processingSeriousError = false

    /**
     * The home-screen bell badge. Sourced from the application-scoped
     * [ContactRequestNotificationService] rather than recomputed inside a
     * `ContactsBasedLiveData`: that class registers its contacts-updated listener
     * only while something observes it, so a contact request that arrived while the
     * user was on another screen never triggered a recount. The service keeps the
     * value current regardless of what is on screen; this LiveData just republishes
     * whatever it holds the moment an observer attaches.
     */
    val notificationCountData: LiveData<Int> =
        contactRequestNotificationService.unseenNotificationCount.asLiveData()
    val notificationCount: Int
        get() = notificationCountData.value ?: 0

    private var contactRequestTimer: AnalyticsTimer? = null

    // end DashPay

    init {
        // Restore the saved filter and prime the service with it.
        val savedDirection: TxFilterType = savedStateHandle[DIRECTION_KEY] ?: TxFilterType.ALL
        _transactionsDirection.value = savedDirection
        txDisplayCacheService.setFilter(savedDirection)
        log.info("STARTUP MainViewModel init at {}", System.currentTimeMillis())

        // ANR FIX — this collector MUST NOT run on the main thread.
        //
        // The blockchain_state row is rewritten roughly once a SECOND for the whole
        // duration of a sync (SdkBlockchainStateService polls the L1 progress feed at
        // 1 Hz and BlockchainStateDataProvider.updateSdkBlockchainState saves the row),
        // so this body executes ~1 Hz while syncing. `launchIn(viewModelScope)` runs it
        // on Dispatchers.Main.immediate, and the body reads
        // `walletData.wallet.lastBlockSeenHeight` — a dashj accessor that takes the
        // wallet's lock. That lock is a FAIR ReentrantReadWriteLock
        // (org.bitcoinj.utils.Threading.readWriteLock passes fair=true), so a reader is
        // queued strictly FIFO behind every pending writer. During a sync of a large
        // wallet the write lock is held for many seconds at a time by
        // Wallet.saveToFileStream (the 5s autosave serializes the ENTIRE wallet
        // protobuf — every transaction and every CoinJoin keychain key) and by
        // receiveFromBlock. The result on a ~100k-transaction CoinJoin wallet is a
        // main-thread stall that exceeds the 5s ANR threshold, once per emission, for
        // the whole sync window — and that stops the moment the sync does.
        //
        // Post-cutover this got strictly worse: BlockchainState.replaying is hardcoded
        // false, so the `if (!state.replaying)` guard below — which used to suppress
        // this log for exactly the duration of a replay — is now always true.
        //
        // flowOn() applies to everything UPSTREAM of it, i.e. to this onEach, so the
        // body now runs on Dispatchers.IO. Everything it touches is safe there:
        // updateSyncStatus/updatePercentage only call LiveData.postValue (designed for
        // background threads), and headersHeight/chainHeight are confined to this one
        // sequential collector.
        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
                updatePercentage(state)
                headersHeight = state.mnlistHeight
                chainHeight = state.bestChainHeight
                if (!state.replaying) {
                    log.info(
                        "blockchain state update: mnlist={}; chain={}; wallet={}",
                        headersHeight,
                        chainHeight,
                        walletData.wallet?.lastBlockSeenHeight
                    )
                }
            }
            .flowOn(Dispatchers.IO)
            .catch { e -> log.error("blockchain state flow error", e) }
            .launchIn(viewModelScope)

        // Once per launch, after sync, remind the user to withdraw any remaining CrowdNode balance.
        combine(
            blockchainStateProvider.observeState().filterNotNull(),
            crowdNodeApi.signUpStatus,
            crowdNodeApi.balance
        ) { state, signUpStatus, balance ->
            Triple(state.isSynced(), signUpStatus, balance.data)
        }
            .onEach { (isSynced, signUpStatus, balance) ->
                val hasAccount = signUpStatus != SignUpStatus.NotStarted
                val hasBalance = balance?.isPositive == true
                val alreadyShown: Boolean = savedStateHandle[CROWDNODE_REMINDER_SHOWN_KEY] ?: false

                if (isSynced && hasAccount && hasBalance && !alreadyShown) {
                    savedStateHandle[CROWDNODE_REMINDER_SHOWN_KEY] = true
                    showCrowdNodeWithdrawalReminder.postCall()
                }
            }
            .catch { e -> log.error("crowdnode withdrawal reminder flow error", e) }
            .launchIn(viewModelScope)

        // Post-upgrade MIXED-FUNDS prompt: fired once the L1 view is current
        // enough that a zero/non-zero CoinJoin balance means something — the
        // service owns that predicate, and it now requires TRUE sync
        // completion (BlockchainState.isSynced()), so the forced prompt can
        // never land on top of a still-wrong mid-scan balance. Never blocks
        // startup: this is a background collector, and a wallet with no mixed
        // funds never emits.
        //
        // The latch here is deliberately IN-MEMORY (not SavedStateHandle): it
        // only throttles the flow re-firing while the sheet is already up.
        // If the activity is recreated before the user chose — e.g. the lock
        // screen engaged and tore the sheet down — the fresh ViewModel starts
        // unlatched and the prompt re-shows, because the only thing that may
        // silence it for good is the permanent latch the service sets when
        // shield/keep-spendable actually runs
        // ([DashPayConfig.MIXED_FUNDS_MIGRATION_DONE], via `shouldPrompt()`).
        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach {
                if (mixedFundsPromptShownThisSession || mixedFundsPromptSuppressed) return@onEach
                // A persisted IN-FLIGHT marker re-shows the sheet in its
                // post-choice PROCESSING presentation (a broadcast whose
                // result is not user-visible yet — e.g. the lock screen tore
                // the sheet down during the confirmation window); otherwise
                // the service decides whether the forced choice is due.
                if (coinJoinFundsMigrationService.inFlightMigration() != null ||
                    coinJoinFundsMigrationService.shouldPrompt()
                ) {
                    mixedFundsPromptShownThisSession = true
                    showMixedFundsMigration.postCall()
                }
            }
            .catch { e -> log.error("mixed-funds migration prompt flow error", e) }
            .launchIn(viewModelScope)

        // One-time post-UPGRADE sync explainer. Observed (not read once)
        // because the marker is armed asynchronously during wallet load, which
        // can land after this ViewModel is constructed.
        dashPayConfig.observe(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING)
            .distinctUntilChanged()
            .onEach { pending -> if (pending == true) showCutoverUpgradeNotice.postCall() }
            .catch { e -> log.error("cutover upgrade-notice flow error", e) }
            .launchIn(viewModelScope)

        // Re-arm the in-flight completion watcher after a process restart:
        // it clears the marker once the migration's result becomes visible
        // (no-op when nothing is in flight).
        coinJoinFundsMigrationService.startInFlightWatcherIfNeeded()

        // Phase 5d: the displayed balance follows whichever engine owns L1
        // this launch. observeTotalBalance() is cutover-aware at the
        // WalletData facade (WalletApplication overlays the SDK's
        // live L1 balance via CutoverUiDataService once the cutover is
        // committed), so EVERY balance consumer switches consistently —
        // pre-cutover this is byte-identical to the dashj-only feed.
        walletData.observeTotalBalance()
            .onEach { _totalBalance.value = it }
            .catch { e -> log.error("total balance flow error", e) }
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .catch { e -> log.error("exchange rate flow error", e) }
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeStaleRates(code)
            }
            .onEach(_rateStale::emit)
            .catch { e -> log.error("stale rates flow error", e) }
            .launchIn(viewModelScope)

        // DashPay
        startContactRequestTimer()

        // Prime the bell badge for this launch. The service holds the count across
        // screens, but on a cold start nothing has computed it yet and the periodic
        // contact sync may be minutes away.
        contactRequestNotificationService.refreshCountInBackground()

        dashPayConfig.observe(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { lastSeenNotification ->
                startContactRequestTimer()
                if (_isBlockchainSynced.value == true) {
                    forceUpdateNotificationCount()
                }
                if (lastSeenNotification != DashPayConfig.DISABLE_NOTIFICATIONS) {
                    userAlertDao.observe(lastSeenNotification)
                        .filterNotNull()
                        .distinctUntilChanged()
                        .onEach { forceUpdateNotificationCount() }
                        .launchIn(viewModelScope)
                }
            }
            .catch { e -> log.error("dashpay notification flow error", e) }
            .launchIn(viewModelScope)

        blockchainStateProvider.observeSyncStage()
            .distinctUntilChanged()
            .onEach { syncStage ->
                if (syncStage == SyncStage.PREBLOCKS || syncStage == SyncStage.BLOCKS && !isPlatformAvailable.value) {
                    isPlatformAvailable.value = if (Constants.SUPPORTS_PLATFORM) {
                        platformService.isPlatformAvailable()
                    } else {
                        false
                    }
                }
                _syncStage.value = syncStage ?: SyncStage.OFFLINE
            }
            .catch { e -> log.error("sync stage flow error", e) }
            .launchIn(viewModelScope)

        // Self-heal a transient DAPI "unavailable" verdict. The sync-stage
        // check above fires only when the STAGE changes (distinctUntilChanged),
        // so a `false` returned once the stage settles at BLOCKS would latch
        // forever — permanently hiding both Join DashPay entry points (the home
        // card and the More-screen DashPay section, each gated on
        // isAbleToCreateIdentity) until an app restart. Retry until Platform is
        // reachable, then stop; nothing here flips it back to false.
        if (Constants.SUPPORTS_PLATFORM) {
            viewModelScope.launch {
                while (isActive && !isPlatformAvailable.value) {
                    if (runCatching { platformService.isPlatformAvailable() }.getOrDefault(false)) {
                        isPlatformAvailable.value = true
                    } else {
                        delay(PLATFORM_AVAILABILITY_RETRY_MS)
                    }
                }
            }
        }
        restoringBackup = config.isRestoringBackup
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun logError(ex: Exception, details: String) {
        analytics.logError(ex, details)
    }

    fun triggerHideBalance() {
        _temporaryHideBalance.value = !hideBalance.value

        if (_temporaryHideBalance.value == true) {
            logEvent(AnalyticsConstants.Home.HIDE_BALANCE)
        } else {
            logEvent(AnalyticsConstants.Home.SHOW_BALANCE)
        }

        viewModelScope.launch { walletUIConfig.set(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT, false) }
    }

    fun logDirectionChangedEvent(direction: TxFilterType) {
        val directionParameter = when (direction) {
            TxFilterType.ALL -> "all_transactions"
            TxFilterType.SENT -> "sent_transactions"
            TxFilterType.RECEIVED -> "received_transactions"
            TxFilterType.GIFT_CARD -> "gift_cards"
        }
        analytics.logEvent(
            AnalyticsConstants.Home.TRANSACTION_FILTER,
            mapOf(
                AnalyticsConstants.Parameter.VALUE to directionParameter
            )
        )

        if (direction == TxFilterType.GIFT_CARD) {
            analytics.logEvent(AnalyticsConstants.DashSpend.FILTER_GIFT_CARD, mapOf())
        }
    }

    fun processDirectTransaction(tx: Transaction) {
        walletData.processDirectTransaction(tx)
    }

    suspend fun getDeviceTimeSkew(force: Boolean): Pair<Boolean, Long> {
        return try {
            val timeSkew = getTimeSkew(force)
            return Pair(abs(timeSkew) > TIME_SKEW_TOLERANCE, timeSkew)
        } catch (_: Exception) {
            // Ignore errors
            Pair(false, 0)
        }
    }

    fun detectUserCountry() = viewModelScope.launch {
        if (walletUIConfig.get(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED) == true) {
            return@launch
        }

        val selectedCurrencyCode = walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY)
        val country = deviceInfo.getSimOrNetworkCountry()

        if (country.isNotEmpty()) {
            updateCurrencyExchange(country.uppercase(Locale.getDefault()))
        } else if (selectedCurrencyCode == null) {
            setDefaultCurrency()
        }
    }

    fun setExchangeCurrencyCodeDetected(currencyCode: String?) {
        viewModelScope.launch {
            currencyCode?.let { walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, it) }
            walletUIConfig.set(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED, true)
        }
    }


    /**
     * Check whether app was ever updated or if it is an installation that was never updated.
     * Show dialog to update if it's being updated or change it automatically.
     *
     * @param countryCode countryCode ISO 3166-1 alpha-2 country code.
     */
    private suspend fun updateCurrencyExchange(countryCode: String) {
        log.info("Updating currency exchange rate based on country: $countryCode")
        val l = Locale("", countryCode)
        val currency = Currency.getInstance(l)
        var newCurrencyCode = currency.currencyCode
        val currentCurrencyCode = walletUIConfig.getExchangeCurrencyCode()

        if (!currentCurrencyCode.equals(newCurrencyCode, ignoreCase = true)) {
            if (config.wasUpgraded()) {
                currencyChangeDetected.postValue(Pair(currentCurrencyCode, newCurrencyCode))
            } else {
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    log.info("found obsolete currency: $newCurrencyCode")
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
                }
                // check to see if we use a different currency code for exchange rates
                newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode)
                log.info("Setting Local Currency: $newCurrencyCode")
                walletUIConfig.set(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED, true)
                walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, newCurrencyCode)
            }
        }

        // Fallback to default
        if (walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY) == null) {
            setDefaultExchangeCurrencyCode()
        }
    }

    private suspend fun setDefaultCurrency() {
        val countryCode = getCurrentCountry()
        log.info("Setting default currency:")

        try {
            log.info("Local Country: $countryCode")
            val l = Locale("", countryCode)
            val currency = Currency.getInstance(l)
            var newCurrencyCode = currency.currencyCode

            if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                log.info("found obsolete currency: $newCurrencyCode")
                newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
            }

            // check to see if we use a different currency code for exchange rates
            newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode)
            log.info("Setting Local Currency: $newCurrencyCode")
            walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, newCurrencyCode)

            // Fallback to default
            if (walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY) == null) {
                setDefaultExchangeCurrencyCode()
            }
        } catch (x: IllegalArgumentException) {
            log.info("Cannot obtain currency for $countryCode: ", x)
            setDefaultExchangeCurrencyCode()
        }
    }

    private suspend fun setDefaultExchangeCurrencyCode() {
        log.info("Using default Country: US")
        log.info(
            "Using default currency: " +
                org.dash.wallet.common.util.Constants.DEFAULT_EXCHANGE_CURRENCY
        )
        walletUIConfig.set(
            WalletUIConfig.SELECTED_CURRENCY,
            org.dash.wallet.common.util.Constants.DEFAULT_EXCHANGE_CURRENCY
        )
    }

    private fun getCurrentCountry(): String {
        return LocaleList.getDefault()[0].country
    }

    // DashPay

    fun reportContactRequestTime() {
        contactRequestTimer?.logTiming()
        contactRequestTimer = null
    }

    private fun forceUpdateNotificationCount() {
        contactRequestNotificationService.refreshCountInBackground()
        viewModelScope.launch(Dispatchers.IO) {
            platformSyncService.updateContactRequests()
        }
    }

    private var lastContactResumeRefreshMs = 0L

    /**
     * Force a network contact-request refresh when the user returns to the
     * home screen. The periodic [PlatformSyncService] poll is scoped to the
     * blockchain service, which auto-tears-down after a few minutes and does
     * not reliably re-arm the ticker across service restarts — leaving
     * multi-minute windows (even in foreground) where contact requests and
     * acceptances aren't fetched. Opening the Contacts screen already forces a
     * poll; this closes the gap for the home screen too so the notification
     * bell refreshes without the user having to drill in.
     *
     * Throttled to [CONTACT_RESUME_REFRESH_THROTTLE_MS] so rapid app switching
     * can't hammer the network, and gated on an established identity + a synced
     * chain (the underlying [PlatformSyncService.updateContactRequests] also
     * self-gates, but skipping the coroutine hop when there's nothing to do
     * keeps resume cheap). Safe no-op for users without a DashPay identity.
     */
    fun refreshContactsOnResume() {
        if (!identityRepository.hasBlockchainIdentity || _isBlockchainSynced.value != true) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastContactResumeRefreshMs < CONTACT_RESUME_REFRESH_THROTTLE_MS) {
            return
        }
        lastContactResumeRefreshMs = now
        forceUpdateNotificationCount()
    }

    suspend fun dismissUsernameCreatedCardIfDone(): Boolean {
        val data = blockchainIdentityDataDao.loadBase()

        if (data.creationState == IdentityCreationState.DONE) {
            identityRepository.doneAndDismiss()
            return true
        }

        return false
    }

    fun dismissUsernameCreatedCard() {
        viewModelScope.launch {
            identityRepository.doneAndDismiss()
        }
    }

    fun joinDashPay() {
        showCreateUsernameEvent.call()
    }

    fun startBlockchainService() {
        walletApplication.startBlockchainService(true)
    }

    suspend fun getProfile(profileId: String): DashPayProfile? {
        return platformRepo.loadProfileByUserId(profileId)
    }

    /**
     * The requested username in the user's own DISPLAY form ("contested1"),
     * never the DPNS-normalized label ("c0ntested1"). The identity restore
     * path historically persisted the normalized label into the USERNAME
     * pref (observed live on the More-screen tile), so when the stored
     * value matches a known request's normalizedLabel, the request's
     * display label wins — see [resolveRequestedUsernameDisplay].
     */
    suspend fun getRequestedUsername(): String {
        val stored = blockchainIdentityDataDao.get(BlockchainIdentityConfig.USERNAME) ?: ""
        if (stored.isEmpty()) return stored
        val identityId = blockchainIdentityDataDao.get(BlockchainIdentityConfig.IDENTITY_ID)
        val candidates = try {
            usernameRequestDao.getRequestsByNormalizedLabel(stored)
        } catch (e: Exception) {
            emptyList()
        }
        return resolveRequestedUsernameDisplay(stored, identityId, candidates)
    }
    suspend fun getInviteHistory() = invitationsDao.loadAll()

    private fun combineLatestData(): Boolean {
        return if (!Constants.SUPPORTS_PLATFORM) {
            log.info("platform is not supported")
            false
        } else {
            val isPlatformAvailable = isPlatformAvailable.value
            val identity = blockchainIdentity.value
            val noIdentityCreatedOrInProgress =
                identity == null || identity.creationState == IdentityCreationState.NONE
            log.info(
                "platform available: {}; no identity creation is progress: {}",
                isPlatformAvailable,
                noIdentityCreatedOrInProgress
            )
            return /*isSynced &&*/ isPlatformAvailable && noIdentityCreatedOrInProgress
        }
    }

    private fun startContactRequestTimer() {
        contactRequestTimer = AnalyticsTimer(
            analytics,
            log,
            AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_RECEIVE
        )
    }

    fun addCoinJoinToWallet() {
        try {
            val encryptionKey = platformRepo.getWalletEncryptionKey()
                ?: throw IllegalStateException("cannot obtain wallet encryption key")
            (walletApplication.wallet as WalletEx).initializeCoinJoin(encryptionKey, 0)
        } catch (e: Exception) {
            log.error("problem adding CoinJoin support to wallet: ", e)
        }
    }

    fun observeMostRecentTransaction() = walletData.observeMostRecentTransaction().distinctUntilChanged()

    /** Delegates to [TxDisplayCacheService.forceRebuildTransactionCache]. */
    fun forceRebuildTransactionCache() = txDisplayCacheService.forceRebuildTransactionCache()

    /** Delegates to [TxDisplayCacheService.getTransactionWrapper]. */
    fun getTransactionWrapper(rowId: String): TransactionWrapper? =
        txDisplayCacheService.getTransactionWrapper(rowId)

    /** Delegates to [TxDisplayCacheService.loadGroupWrapper]. */
    suspend fun loadGroupWrapper(rowId: String): TransactionWrapper? =
        txDisplayCacheService.loadGroupWrapper(rowId)

    fun metadataReminder() {
        viewModelScope.launch {
            if (hasIdentity && !dashPayConfig.isTransactionMetadataInfoShown()) {
                // have there been 10 transactions since the last update?
                val installedDate = dashPayConfig.getMetadataFeatureInstalled()
                walletData.wallet?.let { wallet: Wallet ->
                    // O(transaction count) under dashj's wallet read lock — must never
                    // run on Dispatchers.Main.immediate, which is what a bare
                    // viewModelScope.launch gives you. On a large CoinJoin wallet this
                    // is a multi-second main-thread stall (i.e. a guaranteed ANR).
                    val count = withContext(Dispatchers.IO) {
                        wallet.getTransactions(true).count { tx ->
                            tx.updateTime.time > installedDate
                        }
                    }
                    if (count >= 10) {
                        _remindMetadata.value = true
                    }
                }
            }
        }
    }

    private fun updateSyncStatus(state: BlockchainState) {
        if (_isBlockchainSynced.value != state.isSynced()) {
            _isBlockchainSynced.postValue(state.isSynced())
        }
        _isBlockchainSyncFailed.postValue(state.syncFailed())
        _isNetworkUnavailable.postValue(state.impediments.contains(BlockchainState.Impediment.NETWORK))
    }

    private fun updatePercentage(state: BlockchainState) {
        var percentage = state.percentageSync
        if (state.replaying && state.percentageSync == 100) {
            percentage = 0
        }
        _blockchainSyncPercentage.postValue(percentage)
    }

    /**
     * Selects the cross-chain swap backend before entering the Dash DEX portal so
     * the portal doesn't inherit a stale provider left over from a previous Buy/Sell
     * selection. Mirrors BuyAndSellViewModel.setSwapBackend. SwapKit exposes both Buy
     * and Sell and falls back to Maya automatically when no SwapKit API key is set.
     */
    fun setSwapBackend(backend: SwapBackend) {
        swapProvider.setBackend(backend)
    }

    /**
     * Re-evaluate the mixed-funds prompt after the lock screen is dismissed:
     * if it engaged while the sheet was up (dismissing it) and no choice has
     * been recorded yet, `shouldPrompt()` is still true and the prompt is
     * re-fired. If a migration DID broadcast but its result is not visible
     * yet (the persisted in-flight marker), the sheet is re-fired too — it
     * opens straight in its processing presentation, so the confirmation
     * window is never an unexplained near-zero balance. Once the result
     * lands (marker cleared) this is a no-op.
     */
    fun recheckMixedFundsMigrationPrompt() {
        if (mixedFundsPromptSuppressed) return
        viewModelScope.launch {
            try {
                // The in-flight marker re-shows the PROCESSING presentation
                // (a broadcast migration whose result is not visible yet);
                // shouldPrompt() covers the pre-decision forced choice.
                if (coinJoinFundsMigrationService.inFlightMigration() != null ||
                    coinJoinFundsMigrationService.shouldPrompt()
                ) {
                    mixedFundsPromptShownThisSession = true
                    showMixedFundsMigration.postCall()
                }
            } catch (e: Exception) {
                log.error("mixed-funds migration prompt recheck error", e)
            }
        }
    }

    companion object {
        private const val DIRECTION_KEY = "tx_direction"
        private const val CROWDNODE_REMINDER_SHOWN_KEY = "crowdnode_withdrawal_reminder_shown"

        private const val TIME_SKEW_TOLERANCE = 3600000L // 1 hour
        /** Retry cadence for the self-healing Platform-availability poll (see init). */
        private const val PLATFORM_AVAILABILITY_RETRY_MS = 30_000L

        /**
         * Throttle window for the on-resume contact-request refresh (see
         * [refreshContactsOnResume]). The background [PlatformSyncService]
         * ticker runs every 15s while its sync service is alive; matching that
         * cadence here means returning to the app never fires a redundant
         * network poll on top of a tick that just ran, while still refreshing
         * promptly across the multi-minute gaps left when the blockchain
         * service tears down and the ticker isn't re-armed.
         */
        private const val CONTACT_RESUME_REFRESH_THROTTLE_MS = 15_000L

        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }
}

/**
 * Pick the DISPLAY form of the requested username, pure and host-testable.
 *
 * [stored] is whatever the USERNAME pref holds — the display form the user
 * typed ("contested1") on the direct creation path, but the DPNS-normalized
 * label ("c0ntested1", homoglyphs folded o→0/l→1) when the identity restore
 * path persisted `blockchainIdentity.primaryUsername` from the contested-
 * names index. [candidates] are the locally known username requests whose
 * normalizedLabel equals [stored]; the one belonging to [identityId] (any,
 * when the identity is unknown) carries the display label the contender
 * document was created with. Falls back to [stored] when no request
 * matches — never worse than the old behavior.
 */
internal fun resolveRequestedUsernameDisplay(
    stored: String,
    identityId: String?,
    candidates: List<UsernameRequest>
): String {
    val match = candidates.firstOrNull { identityId == null || it.identity == identityId }
        ?: return stored
    return match.username.ifEmpty { stored }
}
