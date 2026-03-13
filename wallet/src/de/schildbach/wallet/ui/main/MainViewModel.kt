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
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.DeviceInfoProvider
import de.schildbach.wallet.service.MAX_ALLOWED_AHEAD_TIMESKEW
import de.schildbach.wallet.service.MAX_ALLOWED_BEHIND_TIMESKEW
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxWrapperFactory
import de.schildbach.wallet.ui.dashpay.BaseContactsViewModel
import de.schildbach.wallet.ui.dashpay.NotificationCountLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet.util.getTimeSkew
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.PeerGroup.SyncStage
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.CurrencyInfo
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.RateRetrievalState
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.batchAndFilterUpdates
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSetFactory
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val config: Configuration,
    private val walletUIConfig: WalletUIConfig,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    val platformRepo: PlatformRepo,
    private val platformService: PlatformService,
    private val platformSyncService: PlatformSyncService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val savedStateHandle: SavedStateHandle,
    metadataProvider: TransactionMetadataProvider,
    blockchainStateProvider: BlockchainStateProvider,
    val biometricHelper: BiometricHelper,
    private val deviceInfo: DeviceInfoProvider,
    private val invitationsDao: InvitationsDao,
    userAlertDao: UserAlertDao,
    dashPayProfileDao: DashPayProfileDao,
    private val dashPayConfig: DashPayConfig,
    dashPayContactRequestDao: DashPayContactRequestDao,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinService,
    private val txDisplayCacheDao: TxDisplayCacheDao
) : BaseContactsViewModel(blockchainIdentityDataDao, dashPayProfileDao, dashPayContactRequestDao) {
    var restoringBackup: Boolean = false
    private val workerJob = SupervisorJob()

    @VisibleForTesting
    val viewModelWorkerScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + workerJob)

    val balanceDashFormat: MonetaryFormat = config.format.noCode().minDecimals(0)
    val fiatFormat: MonetaryFormat = Constants.LOCAL_FORMAT.minDecimals(0).optionalDecimals(0, 2)

    // In-memory sorted wrapped list — rebuilt when any source changes
    private var wrappedTransactionList: List<TransactionWrapper> = emptyList()

    // Simple flag / flow for WalletFragment and WalletTransactionsFragment
    private val _transactionsLoaded = MutableStateFlow(false)
    val transactionsLoaded: StateFlow<Boolean> = _transactionsLoaded

    private val pagingConfig = PagingConfig(pageSize = 50, prefetchDistance = 20, enablePlaceholders = false)
    // The current active PagingSource — kept so contact changes can trigger manual invalidate().
    // May be a PrebuiltRowsPagingSource (startup) or Room's LimitOffsetPagingSource (live).
    private var currentPagingSource: PagingSource<Int, TxDisplayCacheEntry>? = null

    /**
     * Two-phase data source for the transaction list:
     * - [TxDataSource.Empty]: no data yet (ViewModel just created, cache not loaded)
     * - [TxDataSource.PrebuiltCache]: initial fast display from an in-memory snapshot of
     *   the previous session's display cache — no Room queries per page, just array slices
     * - [TxDataSource.RoomLive]: live Room-backed paging after [rebuildWrappedList] writes
     *   fresh data; Room's [InvalidationTracker] auto-invalidates on metadata/tx changes
     */
    private sealed class TxDataSource {
        object Empty : TxDataSource()
        class PrebuiltCache(val entries: List<TxDisplayCacheEntry>) : TxDataSource()
        object RoomLive : TxDataSource()
    }
    private val _txDataSource = MutableStateFlow<TxDataSource>(TxDataSource.Empty)

    val transactions: Flow<PagingData<HistoryRowView>> = _txDataSource
        .flatMapLatest { source ->
            when (source) {
                is TxDataSource.Empty -> flowOf(PagingData.empty())
                is TxDataSource.PrebuiltCache -> Pager(
                    config = pagingConfig,
                    pagingSourceFactory = {
                        PrebuiltRowsPagingSource(source.entries).also { currentPagingSource = it }
                    }
                ).flow
                is TxDataSource.RoomLive -> Pager(
                    config = pagingConfig,
                    pagingSourceFactory = {
                        txDisplayCacheDao.pagingSource().also { currentPagingSource = it }
                    }
                ).flow
            }
        }
        .map { pagingData ->
            val contacts = contactsByTxId
            pagingData
                .map { entry -> entry.toTransactionRowView(contacts[entry.rowId]) as HistoryRowView }
                .insertSeparators { before: HistoryRowView?, after: HistoryRowView? ->
                    val afterDate = (after as? TransactionRowView)?.let {
                        Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()
                    } ?: return@insertSeparators null
                    val beforeDate = (before as? TransactionRowView)?.let {
                        Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    if (beforeDate != afterDate) HistoryRowView(null, afterDate) else null
                }
        }
        .cachedIn(viewModelScope)

    private val _transactionsDirection = MutableStateFlow(TxFilterType.ALL)
    var transactionsDirection: TxFilterType
        get() = _transactionsDirection.value
        set(value) {
            _transactionsDirection.value = value
            savedStateHandle[DIRECTION_KEY] = value
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
    private var chainHeight: Int = walletData.wallet?.lastBlockSeenHeight ?: 0
    private var chainLockBlockHeight: Int = 0
    private var headersHeight: Int = walletData.wallet?.lastBlockSeenHeight ?: 0
    private val _syncStage = MutableStateFlow(SyncStage.OFFLINE)
    val syncStage: StateFlow<SyncStage>
        get() = _syncStage

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _rateStale = MutableStateFlow(
        RateRetrievalState(
            lastAttemptFailed = false,
            staleRates = false,
            volatile = false
        )
    )
    val rateStale: Flow<RateRetrievalState>
        get() = _rateStale
    val currentStaleRateState
        get() = _rateStale.value
    var rateStaleDismissed = false

    private val _totalBalance = MutableLiveData<Coin>()
    val totalBalance: LiveData<Coin>
        get() = _totalBalance
    private val _mixedBalance = MutableLiveData<Coin>()
    val mixedBalance: LiveData<Coin>
        get() = _mixedBalance

    private var metadata: Map<Sha256Hash, PresentableTxMetadata> = mapOf()
    private var contacts: Map<String, DashPayProfile> = mapOf()
    private var contactsByTxId: Map<String, DashPayProfile> = mapOf()
    private var minContactCreatedDate: LocalDate = LocalDate.now()
    private lateinit var crowdNodeWrapperFactory: FullCrowdNodeSignUpTxSetFactory
    private lateinit var coinJoinWrapperFactory: CoinJoinTxWrapperFactory
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

    // CoinJoin
    val coinJoinMode: Flow<CoinJoinMode>
        get() = coinJoinConfig.observeMode()
    val mixingState: Flow<MixingStatus>
        get() = coinJoinService.observeMixingState()
    val mixingProgress: Flow<Double>
        get() = coinJoinService.observeMixingProgress()
    val mixingSessions: Flow<Int>
        get() = coinJoinService.observeActiveSessions()

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
    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)
    val seriousErrorLiveData = SeriousErrorLiveData(platformRepo)
    var processingSeriousError = false

    val notificationCountData = NotificationCountLiveData(platformRepo, platformSyncService, dashPayConfig, viewModelScope)
    val notificationCount: Int
        get() = notificationCountData.value ?: 0

    private var contactRequestTimer: AnalyticsTimer? = null

    // end DashPay

    init {
        transactionsDirection = savedStateHandle[DIRECTION_KEY] ?: TxFilterType.ALL
        log.info("STARTUP MainViewModel init at {}", System.currentTimeMillis())

        // Load the previous session's display cache into memory so the home screen can
        // show transactions immediately — no Room per-page queries, just array slices.
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val cachedRows = txDisplayCacheDao.getAll()
            log.info("STARTUP tx_display_cache loaded {} rows in {}ms at {}",
                cachedRows.size, System.currentTimeMillis() - t0, System.currentTimeMillis())
            if (cachedRows.isNotEmpty()) {
                _txDataSource.value = TxDataSource.PrebuiltCache(cachedRows)
                _transactionsLoaded.value = true
            }
        }

        // Combine direction filter with wallet-ready signal so that (a) when wallet is loading
        // asynchronously on a background thread, we wait before trying to wrap transactions, and
        // (b) when the direction filter changes after the wallet is ready, we still retrigger.
        combine(_transactionsDirection, walletData.observeWalletReady()) { direction, _ -> direction }
            .flatMapLatest { direction ->
                val wallet = walletData.wallet ?: return@flatMapLatest emptyFlow()
                val filter = TxDirectionFilter(direction, wallet)
                log.info("STARTUP rebuildWrappedList START at {}", System.currentTimeMillis())
                rebuildWrappedList(filter)
                walletData.observeTransactions(true, filter)
                    .batchAndFilterUpdates(BATCHING_PERIOD)
                    .onEach {
                        rebuildWrappedList(filter)
                    }
            }
            .launchIn(viewModelWorkerScope)

        metadataProvider.observePresentableMetadata()
            .onEach { newMetadata ->
                val oldMetadata = this.metadata
                this.metadata = newMetadata

                val changedIds = buildSet<Sha256Hash> {
                    newMetadata.forEach { (id, meta) -> if (meta != oldMetadata[id]) add(id) }
                    oldMetadata.forEach { (id, _) -> if (id !in newMetadata) add(id) }
                }

                if (changedIds.isEmpty()) return@onEach

                // Re-render only the affected wrappers and upsert them into the cache.
                // Room's InvalidationTracker auto-invalidates the pager — no manual call needed.
                val affectedWrappers = wrappedTransactionList.filter { wrapper ->
                    wrapper.transactions.keys.any { it in changedIds }
                }
                if (affectedWrappers.isNotEmpty()) {
                    val newEntries = affectedWrappers.map { wrapper ->
                        val row = TransactionRowView.fromTransactionWrapper(
                            wrapper,
                            walletData.transactionBag,
                            Constants.CONTEXT,
                            contact = null,
                            metadata = newMetadata[wrapper.transactions.keys.first()],
                            chainLockBlockHeight = chainLockBlockHeight
                        )
                        TxDisplayCacheEntry.fromTransactionRowView(row, walletApplication)
                    }
                    // Guard: if this metadata update lost the service info for a row that already
                    // has a service-specific icon (gift card, CrowdNode), preserve the existing
                    // icon/service rather than downgrading to a generic sent/received icon.
                    val rowIds = newEntries.map { it.rowId }
                    val existingByRowId = txDisplayCacheDao.getEntriesByIds(rowIds).associateBy { it.rowId }
                    val entries = newEntries.map { entry ->
                        val existing = existingByRowId[entry.rowId]
                        if (existing != null && existing.service != null && entry.service == null) {
                            entry.copy(
                                service  = existing.service,
                                iconType = existing.iconType,
                                iconBgType = existing.iconBgType
                            )
                        } else {
                            entry
                        }
                    }
                    txDisplayCacheDao.insertAll(entries)
                }
            }
            .launchIn(viewModelWorkerScope)

        platformRepo.observeContacts(
            "",
            UsernameSortOrderBy.LAST_ACTIVITY,
            false
        ).distinctUntilChanged()
        .onEach { contacts ->
            this.minContactCreatedDate = contacts.minOfOrNull { it.dashPayProfile.createdAt }?.let {
               Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            } ?: LocalDate.now()
            val contactsByIdentity = contacts.associate { it.dashPayProfile.userId to it.dashPayProfile }
            this.contacts = contactsByIdentity
            // Clear the txId-keyed cache so resolveAllContacts re-resolves with the new contact list
            this.contactsByTxId = mapOf()
            // Re-resolve contacts against the current wrapped transaction list
            resolveAllContacts()
        }.launchIn(viewModelWorkerScope)

        walletData.observeWalletReset()
            .onEach {
                wrappedTransactionList = emptyList()
                contactsByTxId = mapOf()
                viewModelScope.launch(Dispatchers.IO) { txDisplayCacheDao.deleteAll() }
                walletData.wallet?.let { wallet ->
                    coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
                    crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkParameters, wallet)
                }
                currentPagingSource?.invalidate()
            }
            .launchIn(viewModelScope)

        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
                updatePercentage(state)
                headersHeight = state.mnlistHeight
                chainHeight = state.bestChainHeight
                chainLockBlockHeight = state.chainlockHeight
                if (!state.replaying) {
                    log.info("blockchain state update: {}; {}; {} -> {}", headersHeight, chainHeight, chainLockBlockHeight, walletData.wallet?.lastBlockSeenHeight)
                }
            }
            .launchIn(viewModelWorkerScope)

        // we need the total wallet balance for mixing progress,
        walletData.observeTotalBalance()
            .onEach {
                _totalBalance.value = it
            }
            .launchIn(viewModelScope)

        walletData.observeMixedBalance()
            .onEach {
                _mixedBalance.value = it
            }
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeStaleRates(code)
            }
            .onEach(_rateStale::emit)
            .launchIn(viewModelScope)

        // DashPay
        startContactRequestTimer()

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
                }
            }
            .launchIn(viewModelScope)

        blockchainStateProvider.observeSyncStage()
            .distinctUntilChanged()
            .onEach { syncStage ->
                if (syncStage == PeerGroup.SyncStage.PREBLOCKS || syncStage == PeerGroup.SyncStage.BLOCKS && !isPlatformAvailable.value) {
                    isPlatformAvailable.value = if (Constants.SUPPORTS_PLATFORM) {
                        platformService.isPlatformAvailable()
                    } else {
                        false
                    }
                }
                _syncStage.value = syncStage ?: SyncStage.OFFLINE
            }
            .launchIn(viewModelScope)
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

    suspend fun getCoinJoinMode(): CoinJoinMode {
        return coinJoinConfig.getMode()
    }

    suspend fun getDeviceTimeSkew(force: Boolean): Pair<Boolean, Long> {
        return try {
            val timeSkew = getTimeSkew(force)
            val maxAllowedTimeSkew: Long = if (coinJoinConfig.getMode() == CoinJoinMode.NONE) {
                TIME_SKEW_TOLERANCE
            } else {
                if (timeSkew > 0) MAX_ALLOWED_AHEAD_TIMESKEW * 3 else MAX_ALLOWED_BEHIND_TIMESKEW * 2
            }
            coinJoinService.updateTimeSkew(timeSkew)
            return Pair(abs(timeSkew) > maxAllowedTimeSkew, timeSkew)
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

    private suspend fun rebuildWrappedList(filter: TxDirectionFilter) {
        walletData.wallet?.let { wallet ->
            val t0 = System.currentTimeMillis()
            coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
            crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkParameters, wallet)

            val rawCount = wallet.getTransactions(true).size
            val t1 = System.currentTimeMillis()

            val wrapped = walletData.wrapAllTransactions(crowdNodeWrapperFactory, coinJoinWrapperFactory)
            val t2 = System.currentTimeMillis()

            val filtered = wrapped.filter { it.passesFilter(filter, metadata) }
            val t3 = System.currentTimeMillis()

            wrappedTransactionList = filtered.sortedByDescending { it.groupDate }
            val t4 = System.currentTimeMillis()

            log.info("rebuildWrappedList: {} raw txs → {} wrappers → {} filtered → {} sorted | " +
                "getTransactions={}ms wrapAll={}ms filter={}ms sort={}ms total={}ms",
                rawCount, wrapped.size, filtered.size, wrappedTransactionList.size,
                t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0)

            // Update the Room display cache — Room auto-invalidates the pager.
            updateDisplayCache(wrappedTransactionList)

            // Signal that the live data is ready (shortcut bar, empty-view guard).
            // Do this BEFORE contact resolution — contacts can take seconds for DashPay
            // users and should not block the UI from becoming fully interactive.
            _transactionsLoaded.value = true

            // Resolve contacts in the background.  Launch as a separate coroutine so
            // it does not block the next rebuildWrappedList call when batch tx updates
            // arrive during sync.  viewModelWorkerScope ensures thread-safe access to
            // contactsByTxId without races against the metadata observer.
            log.info("STARTUP rebuildWrappedList DONE (_transactionsLoaded=true) at {}", System.currentTimeMillis())
            viewModelWorkerScope.launch { resolveAllContacts() }
        }
    }

    /**
     * Render [wrappers] to [TxDisplayCacheEntry] rows and write to Room.
     *
     * To minimize latency, the first [INITIAL_RENDER_SIZE] rows are written atomically
     * (replacing the old cache) so the UI shows live data as fast as possible.  The
     * remaining rows are inserted in a second pass without another full replace — they
     * don't affect scroll position since the first page is already visible.
     *
     * Runs synchronously on the caller's coroutine context ([viewModelWorkerScope]) to
     * avoid racing with concurrent rebuilds.
     */
    private suspend fun updateDisplayCache(wrappers: List<TransactionWrapper>) {
        val t0 = System.currentTimeMillis()

        fun renderEntry(wrapper: TransactionWrapper): TxDisplayCacheEntry {
            val row = TransactionRowView.fromTransactionWrapper(
                wrapper,
                walletData.transactionBag,
                Constants.CONTEXT,
                contact = null,
                metadata = metadata[wrapper.transactions.keys.first()],
                chainLockBlockHeight = chainLockBlockHeight
            )
            return TxDisplayCacheEntry.fromTransactionRowView(row, walletApplication)
        }

        val initialBatch = wrappers.take(INITIAL_RENDER_SIZE).map { renderEntry(it) }
        // Atomic replace — PagingSource sees either old data or the new first page, never empty.
        txDisplayCacheDao.replaceAll(initialBatch)
        // Switch from PrebuiltCache to RoomLive now that fresh data is in Room.
        // Room's InvalidationTracker will auto-invalidate after replaceAll, so the pager
        // reloads from the DB (which now has the live first page).
        _txDataSource.value = TxDataSource.RoomLive
        val t1 = System.currentTimeMillis()
        log.info("updateDisplayCache: first {} rows in {}ms (switched to RoomLive)", initialBatch.size, t1 - t0)

        if (wrappers.size > INITIAL_RENDER_SIZE) {
            val rest = wrappers.drop(INITIAL_RENDER_SIZE).map { renderEntry(it) }
            txDisplayCacheDao.insertAll(rest)
            log.info("updateDisplayCache: remaining {} rows in {}ms (total {}ms)",
                rest.size, System.currentTimeMillis() - t1, System.currentTimeMillis() - t0)
        }
    }

    companion object {
        private const val BATCHING_PERIOD = 500L
        private const val DIRECTION_KEY = "tx_direction"
        private const val TIME_SKEW_TOLERANCE = 3600000L // seconds (1 hour)
        /** Number of rows rendered and written to Room immediately for fast first-paint. */
        private const val INITIAL_RENDER_SIZE = 100

        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }

    /**
     * Resolve contacts for all transactions in [wrappedTransactionList] and populate
     * [contactsByTxId].  Skips transactions that are entirely self-sends or that predate
     * any contact relationship.  Results are merged into the existing map (so earlier
     * resolutions are preserved until overwritten).
     *
     * After resolution, the current [PagingSource] is invalidated so the `.map {}` flow
     * on the pager picks up the new contacts from [contactsByTxId].
     *
     * Must be called on [viewModelWorkerScope] (single-threaded) so that reads/writes
     * to [contactsByTxId] are safe.
     */
    private suspend fun resolveAllContacts() {
        if (contacts.isEmpty()) return
        if (!platformRepo.hasBlockchainIdentity) return

        val txsToResolve = wrappedTransactionList
            .map { it.transactions.values.first() }
            .filter { tx ->
                // Skip self-sends (internal transfers) -- they cannot have contacts
                !tx.isEntirelySelf(walletData.transactionBag) &&
                    // Skip transactions that predate any contact relationship
                    tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() >= minContactCreatedDate &&
                    // Skip transactions already resolved
                    contactsByTxId[tx.txId.toString()] == null
            }

        if (txsToResolve.isEmpty()) return

        val resolved = coroutineScope {
            txsToResolve
                .map { tx ->
                    async(Dispatchers.IO) {
                        try {
                            platformRepo.blockchainIdentity.getContactForTransaction(tx)?.let { id ->
                                contacts[id]?.let { profile -> tx.txId.toString() to profile }
                            }
                        } catch (e: Exception) {
                            log.warn("failed to resolve contact for tx {}: {}", tx.txId, e.message)
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

        if (resolved.isNotEmpty()) {
            contactsByTxId = contactsByTxId + resolved
            currentPagingSource?.invalidate()
            log.info("resolveAllContacts: resolved {} contacts for {} candidates",
                resolved.size, txsToResolve.size)
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
            // This is to prevent showing 100% when using the Rescan blockchain function.
            // The first few broadcast blockchainStates are with percentage sync at 100%
            percentage = 0
        }
        _blockchainSyncPercentage.postValue(percentage)
    }

    private fun TransactionWrapper.passesFilter(
        filter: TxDirectionFilter,
        metadata: Map<Sha256Hash, PresentableTxMetadata>
    ): Boolean {
        return (filter.direction == TxFilterType.GIFT_CARD && isGiftCard(metadata)) ||
            transactions.values.any { tx -> filter.matches(tx) }
    }

    private fun TransactionWrapper.isGiftCard(metadata: Map<Sha256Hash, PresentableTxMetadata>): Boolean {
        return ServiceName.isDashSpend(metadata[transactions.values.first().txId]?.service)
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
        notificationCountData.onContactsUpdated()
        viewModelScope.launch(Dispatchers.IO) {
            platformSyncService.updateContactRequests()
        }
    }

    suspend fun dismissUsernameCreatedCardIfDone(): Boolean {
        val data = blockchainIdentityDataDao.loadBase()

        if (data.creationState == IdentityCreationState.DONE) {
            platformRepo.doneAndDismiss()
            return true
        }

        return false
    }

    fun dismissUsernameCreatedCard() {
        viewModelScope.launch {
            platformRepo.doneAndDismiss()
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

    suspend fun getRequestedUsername(): String =
        blockchainIdentityDataDao.get(BlockchainIdentityConfig.USERNAME) ?: ""
    suspend fun getInviteHistory() = invitationsDao.loadAll()

    private fun combineLatestData(): Boolean {
        return if (!Constants.SUPPORTS_PLATFORM) {
            log.info("platform is not supported")
            false
        } else {
            val isPlatformAvailable = isPlatformAvailable.value
            val noIdentityCreatedOrInProgress =
                (blockchainIdentity.value == null) || blockchainIdentity.value!!.creationState == IdentityCreationState.NONE
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

    /**
     * Look up the live [TransactionWrapper] by its row ID (txId hex for individuals,
     * or groupId for CoinJoin/CrowdNode groups).  Returns null if the live rebuild has not
     * finished yet (caller should handle gracefully).
     */
    fun getTransactionWrapper(rowId: String): TransactionWrapper? =
        wrappedTransactionList.find { it.id == rowId }

    fun metadataReminder() {
        viewModelScope.launch {
            if (hasIdentity && !dashPayConfig.isTransactionMetadataInfoShown()) {
                // have there been 10 transactions since the last update?
                val installedDate = dashPayConfig.getMetadataFeatureInstalled()
                walletData.wallet?.let { wallet: Wallet ->
                    var count = 0
                    wallet.getTransactions(true).forEach { tx ->
                        if (tx.updateTime.time > installedDate) {
                            count++
                        }
                    }
                    if (count >= 10) {
                        _remindMetadata.value = true
                    }
                }
            }
        }
    }
}
