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
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.database.entity.TxGroupCacheEntry
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
import de.schildbach.wallet.transactions.coinjoin.CoinJoinMixingTxSet
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.TransactionBag
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
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSetFactory
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val txGroupCacheDao: TxGroupCacheDao
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

    /** True while a full cache rebuild is in progress (first run or user-initiated refresh). */
    private val _isBuildingCache = MutableStateFlow(false)
    val isBuildingCache: StateFlow<Boolean> = _isBuildingCache

    private val pagingConfig = PagingConfig(pageSize = 50, prefetchDistance = 20, enablePlaceholders = false)
    // The current active PagingSource — kept so contact changes can trigger manual invalidate().
    // Only used for the RoomLive paging source; PrebuiltCache uses a separate in-memory source.
    // MutableStateFlow provides atomic read/write across Paging's internal threads and coroutine scopes.
    private val _currentPagingSource = MutableStateFlow<PagingSource<Int, TxDisplayCacheEntry>?>(null)

    /**
     * Two-phase data source for the transaction list:
     * - [TxDataSource.Empty]: no data yet (ViewModel just created, cache not loaded)
     * - [TxDataSource.PrebuiltCache]: initial fast display — rows already include date-header
     *   entries interleaved, so no [insertSeparators] transform is needed on this path
     * - [TxDataSource.RoomLive]: live Room-backed paging after [rebuildWrappedList] writes
     *   fresh data; Room's [InvalidationTracker] auto-invalidates on metadata/tx changes
     */
    private sealed class TxDataSource {
        object Empty : TxDataSource()
        class PrebuiltCache(val rows: List<HistoryRowView>) : TxDataSource()
        // Filter is stored separately in _liveFilterFlag so filter changes can be applied
        // via PagingSource.invalidate() without cancelling the Pager (avoids flicker).
        object RoomLive : TxDataSource()
    }
    private val _txDataSource = MutableStateFlow<TxDataSource>(TxDataSource.Empty)
    // Holds the active Room WHERE-clause filter. Written before _txDataSource switches to
    // RoomLive, and updated in-place (+ invalidate) when the user changes the filter tab.
    private val _liveFilterFlag = MutableStateFlow(0)

    /**
     * Cache rows for the fast startup phase, exposed so [WalletTransactionsFragment] can call
     * [CacheTransactionAdapter.submitList] directly — a single background DiffUtil + one
     * main-thread handler post — instead of going through [PagingDataAdapter.submitData]'s
     * multi-dispatch coroutine chain which is slow when the main looper is congested at startup.
     */
    val cachedRows: StateFlow<List<HistoryRowView>> = _txDataSource
        .map { source -> if (source is TxDataSource.PrebuiltCache) source.rows else emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transactions: Flow<PagingData<HistoryRowView>> = _txDataSource
        .flatMapLatest { source ->
            when (source) {
                is TxDataSource.Empty -> flowOf(PagingData.empty())
                // Cache rows are delivered via cachedRows / CacheTransactionAdapter.submitList()
                // to avoid PagingDataAdapter's slow coroutine-dispatch chain at startup.
                is TxDataSource.PrebuiltCache -> flowOf(PagingData.empty())
                is TxDataSource.RoomLive -> {
                    Pager(
                        config = pagingConfig,
                        pagingSourceFactory = {
                            // Read the filter flag at factory-call time so that invalidate()-based
                            // filter changes pick up the new flag without recreating the Pager.
                            txDisplayCacheDao.pagingSource(_liveFilterFlag.value).also { _currentPagingSource.value = it }
                        }
                    ).flow.map { pagingData ->
                        pagingData
                            // Read contactsByTxId at render time (not at branch-entry) so each page
                            // after an invalidate() sees the contacts resolved since the Pager started.
                            // @Volatile on the field ensures cross-thread visibility from workerScope.
                            .map { entry -> entry.toTransactionRowView(contactsByTxId[entry.rowId]) as HistoryRowView }
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
                }
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

    private val _rateStale = MutableStateFlow(RateRetrievalState.DEFAULT)
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
    // @Volatile so Paging's IO-thread .map lambda always sees the latest write from
    // viewModelWorkerScope without the stale-snapshot problem of a plain var capture.
    @Volatile private var contactsByTxId: Map<String, DashPayProfile> = mapOf()
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

        // Load the previous session's display cache and interleave date headers on the IO thread
        // so the home screen can show transactions immediately without any PagingData transforms.
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val cachedRows = txDisplayCacheDao.getAll()
            log.info("STARTUP tx_display_cache loaded {} rows in {}ms at {}",
                cachedRows.size, System.currentTimeMillis() - t0, System.currentTimeMillis())
            if (cachedRows.isNotEmpty()) {
                val contacts = contactsByTxId
                val historyRows = ArrayList<HistoryRowView>(cachedRows.size + 32)
                var prevDate: LocalDate? = null
                for (entry in cachedRows) {
                    val txRow = entry.toTransactionRowView(contacts[entry.rowId])
                    val date = Instant.ofEpochMilli(txRow.time)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    if (date != prevDate) {
                        historyRows.add(HistoryRowView(null, date))
                        prevDate = date
                    }
                    historyRows.add(txRow)
                }
                // Only promote to PrebuiltCache when the direction handler hasn't
                // already switched to RoomLive (initializeFactoriesFromCache is fast
                // and may beat this IO coroutine on subsequent startups).
                if (_txDataSource.value is TxDataSource.Empty) {
                    _txDataSource.value = TxDataSource.PrebuiltCache(historyRows)
                    _transactionsLoaded.value = true
                }
            }
        }

        _transactionsDirection
            .flatMapLatest { direction ->
                val filter = TxDirectionFilter(direction, walletData.wallet!!)
                if (wrappedTransactionList.isEmpty()) {
                    if (txDisplayCacheDao.getCount() > 0) {
                        // Display cache is populated — start the Room pager immediately so it
                        // pre-loads the first page while factory initialisation runs.  By the
                        // time the Fragment subscribes (~100–200ms later), the first page of
                        // rows is already in the pager's cache and appears with no extra wait.
                        _liveFilterFlag.value = filter.direction.toFilterFlag()
                        _txDataSource.value = TxDataSource.RoomLive
                        _transactionsLoaded.value = true
                        initializeFactoriesFromCache()
                    } else {
                        // First-ever run (no display cache) — must do full rebuild to populate caches.
                        rebuildWrappedList(filter)
                    }
                } else {
                    // Direction changed — display cache already has all txs with filterFlags set.
                    // Update the filter flag and invalidate the current PagingSource so Paging
                    // calls pagingSourceFactory() with the new flag.  This keeps the existing
                    // Pager alive and avoids the flatMapLatest cancel/restart flicker.
                    log.info("direction changed to {} — switching filter flag only", direction)
                    _liveFilterFlag.value = filter.direction.toFilterFlag()
                    _currentPagingSource.value?.invalidate()
                }
                walletData.observeTransactions(true, filter)
                    .batchAndFilterUpdates(BATCHING_PERIOD)
                    .onEach { txs ->
                        // log.info("tx batch update: {} transactions", txs.size)
                        updateWrappedListForTransactions(txs, filter)
                    }
            }
            .catch { e -> log.error("transactionsDirection flow error", e) }
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
                val inMemoryWrappers = wrappedTransactionList.filter { wrapper ->
                    wrapper.transactions.keys.any { it in changedIds }
                }
                // Under lazy loading, wrappers for historical txs may not be in memory yet.
                // Look them up from the group cache and load on demand.
                val inMemoryTxIds = inMemoryWrappers.flatMap { it.transactions.keys }.toSet()
                val missingTxIds = changedIds.filter { it !in inMemoryTxIds }.map { it.toString() }
                val lazyWrappers = if (missingTxIds.isNotEmpty()) {
                    val cacheEntries = txGroupCacheDao.getGroupsForTxIds(missingTxIds)
                    val loadedById = HashMap<String, TransactionWrapper>()
                    cacheEntries.mapNotNull { entry ->
                        loadedById[entry.groupId]
                            ?: loadWrapperOnDemand(entry.groupId, entry.wrapperType)
                                ?.also { loadedById[it.id] = it }
                    }.distinctBy { it.id }
                } else emptyList()
                val affectedWrappers = (inMemoryWrappers + lazyWrappers).distinctBy { it.id }
                if (affectedWrappers.isNotEmpty()) {
                    val newEntries = affectedWrappers.map { wrapper ->
                        // Prefer the tx that actually changed metadata (so the memo is picked up);
                        // fall back to the first tx key for grouped wrappers with no direct match.
                        val txId = wrapper.transactions.keys.firstOrNull { it in changedIds }
                            ?: wrapper.transactions.keys.first()
                        val row = TransactionRowView.fromTransactionWrapper(
                            wrapper,
                            walletData.transactionBag,
                            Constants.CONTEXT,
                            contact = contactsByTxId[txId.toString()],
                            metadata = newMetadata[txId],
                            chainLockBlockHeight = chainLockBlockHeight
                        )
                        TxDisplayCacheEntry.fromTransactionRowView(row, walletApplication, computeFilterFlags(wrapper))
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
            .catch { e -> log.error("metadata flow error", e) }
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
        }
        .catch { e -> log.error("contacts flow error", e) }
        .launchIn(viewModelWorkerScope)

        walletData.observeWalletReset()
            .onEach {
                wrappedTransactionList = emptyList()
                contactsByTxId = mapOf()
                viewModelScope.launch(Dispatchers.IO) {
                    txDisplayCacheDao.deleteAll()
                    txGroupCacheDao.deleteAll()
                }
                walletData.wallet?.let { wallet ->
                    coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
                    crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkParameters, wallet)
                }
                _currentPagingSource.value?.invalidate()
            }
            .catch { e -> log.error("wallet reset flow error", e) }
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
            .catch { e -> log.error("blockchain state flow error", e) }
            .launchIn(viewModelWorkerScope)

        // we need the total wallet balance for mixing progress,
        walletData.observeTotalBalance()
            .onEach {
                _totalBalance.value = it
            }
            .catch { e -> log.error("total balance flow error", e) }
            .launchIn(viewModelScope)

        walletData.observeMixedBalance()
            .onEach {
                _mixedBalance.value = it
            }
            .catch { e -> log.error("mixed balance flow error", e) }
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
            .catch { e -> log.error("dashpay notification flow error", e) }
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
            .catch { e -> log.error("sync stage flow error", e) }
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
        _isBuildingCache.value = true
        try {
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

                // Persist ALL (unfiltered) wrappers to the group cache so future startups
                // can skip wrapAllTransactions() and reconstruct from stored group structure.
                persistGroupCache(wrapped)

                // Update the Room display cache with ALL wrappers — filtered by SQL at query time.
                updateDisplayCache(wrapped.toList(), filter.direction.toFilterFlag())

                // Signal that the live data is ready (shortcut bar, empty-view guard).
                // Do this BEFORE contact resolution — contacts can take seconds for DashPay
                // users and should not block the UI from becoming fully interactive.
                _isBuildingCache.value = false
                _transactionsLoaded.value = true

                // Resolve contacts in the background.  Launch as a separate coroutine so
                // it does not block the next rebuildWrappedList call when batch tx updates
                // arrive during sync.  viewModelWorkerScope ensures thread-safe access to
                // contactsByTxId without races against the metadata observer.
                log.info("STARTUP rebuildWrappedList DONE (_transactionsLoaded=true) at {}", System.currentTimeMillis())
                viewModelWorkerScope.launch { resolveAllContacts() }
            }
        } finally {
            _isBuildingCache.value = false
        }
    }

    /**
     * Render [wrappers] to [TxDisplayCacheEntry] rows and write to Room, then switch
     * the pager to [TxDataSource.RoomLive].  All rows are written before the switch so
     * the pager never sees a partial table.
     *
     * Runs synchronously on the caller's coroutine context ([viewModelWorkerScope]) to
     * avoid racing with concurrent rebuilds.
     */
    private suspend fun updateDisplayCache(wrappers: List<TransactionWrapper>, filterFlag: Int) {
        val t0 = System.currentTimeMillis()

        fun renderEntry(wrapper: TransactionWrapper): TxDisplayCacheEntry {
            val txId = wrapper.transactions.keys.first()
            val row = TransactionRowView.fromTransactionWrapper(
                wrapper,
                walletData.transactionBag,
                Constants.CONTEXT,
                contact = contactsByTxId[txId.toString()],
                metadata = metadata[txId],
                chainLockBlockHeight = chainLockBlockHeight
            )
            return TxDisplayCacheEntry.fromTransactionRowView(row, walletApplication, computeFilterFlags(wrapper))
        }

        val allEntries = wrappers.map { renderEntry(it) }
        txDisplayCacheDao.replaceAll(allEntries)
        log.info("updateDisplayCache: {} rows in {}ms", allEntries.size, System.currentTimeMillis() - t0)
        _liveFilterFlag.value = filterFlag
        _txDataSource.value = TxDataSource.RoomLive
    }

    /**
     * Persist the group structure (all wrappers, unfiltered) to Room so that
     * [buildWrappedListFromCache] can reconstruct [wrappedTransactionList] at
     * the next startup without calling [wrapAllTransactions].
     */
    private suspend fun persistGroupCache(wrappers: Collection<TransactionWrapper>) {
        val entries = wrappers.flatMap { wrapper ->
            val type = when (wrapper) {
                is CoinJoinMixingTxSet          -> TxGroupCacheEntry.TYPE_COINJOIN
                is FullCrowdNodeSignUpTxSet      -> TxGroupCacheEntry.TYPE_CROWDNODE
                else                            -> TxGroupCacheEntry.TYPE_SINGLE
            }
            wrapper.transactions.values
                .sortedBy { it.updateTime }
                .mapIndexed { index, tx ->
                    TxGroupCacheEntry(
                        groupId     = wrapper.id,
                        txId        = tx.txId.toString(),
                        wrapperType = type,
                        groupDate   = wrapper.groupDate.toString(),
                        sortOrder   = index
                    )
                }
        }
        txGroupCacheDao.replaceAll(entries)
    }

    /**
     * Lightweight factory initialisation used at startup when the display cache already
     * contains rendered rows.  Only loads "active" groups — today's CoinJoin sessions and
     * any in-progress CrowdNode signup — into [coinJoinWrapperFactory] and
     * [crowdNodeWrapperFactory], and adds their wrappers to [wrappedTransactionList].
     *
     * All other historical wrappers are left out of [wrappedTransactionList] until the user
     * taps a row ([loadGroupWrapper]) or an incremental tx update arrives
     * ([updateWrappedListForTransactions] handles lazy-loading via [loadWrapperOnDemand]).
     */
    private suspend fun initializeFactoriesFromCache() {
        val wallet = walletData.wallet ?: return
        val t0 = System.currentTimeMillis()
        coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
        crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkParameters, wallet)

        val today = LocalDate.now().toString()
        val activeEntries = txGroupCacheDao.getActiveGroups(today)
        val byGroup = activeEntries.groupBy { it.groupId }
        val activeWrappers = mutableListOf<TransactionWrapper>()

        for ((groupId, rows) in byGroup) {
            val wrapperType = rows.first().wrapperType
            val txs = rows.sortedBy { it.sortOrder }.mapNotNull { row ->
                try {
                    wallet.getTransaction(Sha256Hash.wrap(row.txId))
                } catch (e: IllegalArgumentException) {
                    log.error("initializeFactoriesFromCache: invalid txId bytes for group {}", groupId, e)
                    null
                }
            }
            if (txs.isEmpty()) continue

            val wrapper = when (wrapperType) {
                TxGroupCacheEntry.TYPE_COINJOIN -> {
                    txs.forEach { coinJoinWrapperFactory.tryInclude(it) }
                    coinJoinWrapperFactory.wrappers.find { it.id == groupId }
                }
                TxGroupCacheEntry.TYPE_CROWDNODE -> {
                    txs.forEach { crowdNodeWrapperFactory.tryInclude(it) }
                    crowdNodeWrapperFactory.wrappers.find { it.id == groupId }
                }
                else -> null
            }
            wrapper?.let { activeWrappers.add(it) }
        }

        wrappedTransactionList = activeWrappers.sortedByDescending { it.groupDate }
        log.info("initializeFactoriesFromCache: {} active groups loaded in {}ms",
            byGroup.size, System.currentTimeMillis() - t0)
    }

    /**
     * Loads a single group from [tx_group_cache] and feeds its transactions through the
     * appropriate factory, returning the reconstructed [TransactionWrapper].  The resulting
     * wrapper is also appended to [wrappedTransactionList] for future in-memory lookups.
     *
     * Called by [updateWrappedListForTransactions] when it encounters a tx that belongs to a
     * cached group not yet in memory, and by [loadGroupWrapper] for user-tap driven loading.
     */
    private suspend fun loadWrapperOnDemand(groupId: String, wrapperType: String): TransactionWrapper? {
        val wallet = walletData.wallet ?: return null
        val entries = txGroupCacheDao.getGroupEntries(groupId)
        val txs = entries.sortedBy { it.sortOrder }.mapNotNull { row ->
            try {
                wallet.getTransaction(Sha256Hash.wrap(row.txId))
            } catch (e: IllegalArgumentException) {
                log.error("loadWrapperOnDemand: invalid txId bytes for group {}", groupId, e)
                null
            }
        }
        if (txs.isEmpty()) return null

        val wrapper = when (wrapperType) {
            TxGroupCacheEntry.TYPE_COINJOIN -> {
                txs.forEach { coinJoinWrapperFactory.tryInclude(it) }
                coinJoinWrapperFactory.wrappers.find { it.id == groupId }
            }
            TxGroupCacheEntry.TYPE_CROWDNODE -> {
                txs.forEach { crowdNodeWrapperFactory.tryInclude(it) }
                crowdNodeWrapperFactory.wrappers.find { it.id == groupId }
            }
            else -> txs.firstOrNull()?.let { createSingleTxWrapper(it) }
        } ?: return null

        // Add to in-memory list so subsequent lookups are instant
        if (wrappedTransactionList.none { it.id == wrapper.id }) {
            wrappedTransactionList = (wrappedTransactionList + wrapper)
                .sortedByDescending { it.groupDate }
        }
        return wrapper
    }

    /**
     * Public suspend entry-point for [WalletTransactionsFragment]: returns the live
     * [TransactionWrapper] for [rowId], loading it from [tx_group_cache] on demand if it
     * is not already in [wrappedTransactionList].
     *
     * Must be called from a coroutine that can block on IO (e.g. [lifecycleScope.launch]).
     */
    suspend fun loadGroupWrapper(rowId: String): TransactionWrapper? {
        wrappedTransactionList.find { it.id == rowId }?.let { return it }
        // Not in memory — look up type from group cache and reconstruct on demand
        val firstEntry = txGroupCacheDao.getGroupEntries(rowId).firstOrNull() ?: return null
        return loadWrapperOnDemand(rowId, firstEntry.wrapperType)
    }

    /**
     * Incremental update triggered by [walletData.observeTransactions].
     *
     * For each transaction in [txs]:
     * - If it is already in a wrapper (confidence update): update the Transaction reference
     *   and re-render that wrapper's cache entries.
     * - If it is new: try to include it in the CoinJoin or CrowdNode factory; otherwise
     *   create a standalone single-tx wrapper. Update both caches.
     *
     * Never calls [wrapAllTransactions].
     */
    private suspend fun updateWrappedListForTransactions(txs: List<Transaction>, filter: TxDirectionFilter) {
        // Build txId (hex) → wrapper lookup from the current list
        val txIdToWrapper = HashMap<String, TransactionWrapper>(wrappedTransactionList.size * 4)
        wrappedTransactionList.forEach { wrapper ->
            wrapper.transactions.keys.forEach { txId ->
                txIdToWrapper[txId.toString()] = wrapper
            }
        }

        val mutableList = wrappedTransactionList.toMutableList()
        val affectedWrappers = mutableSetOf<TransactionWrapper>()

        // Separate txs already tracked in memory from unknown ones.
        val unknownTxs = mutableListOf<Transaction>()
        for (tx in txs) {
            val existing = txIdToWrapper[tx.txId.toString()]
            if (existing != null) {
                // Confidence/metadata update for a known tx — refresh the reference.
                existing.transactions[tx.txId] = tx
                affectedWrappers.add(existing)
            } else {
                unknownTxs.add(tx)
            }
        }

        if (unknownTxs.isNotEmpty()) {
            // Batch-query the group cache for all unknown txIds in one round-trip.
            // This handles the case where wrappedTransactionList is sparse at startup
            // (lazy initialisation) but the tx belongs to a historically cached group.
            val unknownKeys = unknownTxs.map { it.txId.toString() }
            val cachedByTxId = txGroupCacheDao.getGroupsForTxIds(unknownKeys).associateBy { it.txId }
            // Track wrappers loaded in this pass to avoid duplicate loadWrapperOnDemand calls
            val loadedById = mutableList.associateByTo(HashMap()) { it.id }

            for (tx in unknownTxs) {
                val txKey = tx.txId.toString()
                val cacheEntry = cachedByTxId[txKey]

                if (cacheEntry != null) {
                    // Tx is in an existing group that wasn't loaded into memory yet — lazy-load it.
                    val wrapper = loadedById[cacheEntry.groupId]
                        ?: loadWrapperOnDemand(cacheEntry.groupId, cacheEntry.wrapperType)
                            ?.also { loadedById[it.id] = it }
                    if (wrapper != null) {
                        wrapper.transactions[tx.txId] = tx
                        affectedWrappers.add(wrapper)
                        if (wrapper.passesFilter(filter, metadata) && mutableList.none { it.id == wrapper.id }) {
                            mutableList.add(wrapper)
                        }
                        continue  // move to next tx — no factory involvement needed
                    }
                }

                // Genuinely new transaction — run through factories.
                var added = false

                val (cjIncluded, cjWrapper) = coinJoinWrapperFactory.tryInclude(tx)
                if (cjIncluded && cjWrapper != null) {
                    if (cjWrapper.passesFilter(filter, metadata) &&
                        mutableList.none { it.id == cjWrapper.id }) {
                        mutableList.add(cjWrapper)
                    }
                    affectedWrappers.add(cjWrapper)
                    added = true
                }

                if (!added) {
                    val (cnIncluded, cnWrapper) = crowdNodeWrapperFactory.tryInclude(tx)
                    if (cnIncluded && cnWrapper != null) {
                        if (cnWrapper.passesFilter(filter, metadata) &&
                            mutableList.none { it.id == cnWrapper.id }) {
                            mutableList.add(cnWrapper)
                        }
                        affectedWrappers.add(cnWrapper)
                        added = true
                    }
                }

                if (!added) {
                    val wrapper = createSingleTxWrapper(tx)
                    // Always write to the display cache regardless of the active filter so
                    // the tx appears when the user switches to a filter that includes it.
                    // (Same pattern as CoinJoin/CrowdNode above.)  Only gate mutableList.add.
                    affectedWrappers.add(wrapper)
                    if (wrapper.passesFilter(filter, metadata)) {
                        mutableList.add(wrapper)
                    }
                }
            }
        }

        if (mutableList.size != wrappedTransactionList.size) {
            mutableList.sortByDescending { it.groupDate }
        }
        wrappedTransactionList = mutableList

        if (affectedWrappers.isEmpty()) return

        // Update display cache for affected wrappers — store all, filtering is done by SQL.
        val displayEntries = affectedWrappers
            .map { wrapper ->
                val txId = wrapper.transactions.keys.first()
                val row = TransactionRowView.fromTransactionWrapper(
                    wrapper, walletData.transactionBag, Constants.CONTEXT,
                    contact = contactsByTxId[txId.toString()],
                    metadata = metadata[txId],
                    chainLockBlockHeight = chainLockBlockHeight
                )
                TxDisplayCacheEntry.fromTransactionRowView(row, walletApplication, computeFilterFlags(wrapper))
            }
        if (displayEntries.isNotEmpty()) {
            txDisplayCacheDao.insertAll(displayEntries)
        }

        // Update group cache — one row per (wrapper, tx)
        val groupEntries = affectedWrappers.flatMap { wrapper ->
            val type = when (wrapper) {
                is CoinJoinMixingTxSet          -> TxGroupCacheEntry.TYPE_COINJOIN
                is FullCrowdNodeSignUpTxSet      -> TxGroupCacheEntry.TYPE_CROWDNODE
                else                            -> TxGroupCacheEntry.TYPE_SINGLE
            }
            wrapper.transactions.values
                .sortedBy { it.updateTime }
                .mapIndexed { index, tx ->
                    TxGroupCacheEntry(
                        groupId     = wrapper.id,
                        txId        = tx.txId.toString(),
                        wrapperType = type,
                        groupDate   = wrapper.groupDate.toString(),
                        sortOrder   = index
                    )
                }
        }
        txGroupCacheDao.insertAll(groupEntries)
        // Room's InvalidationTracker auto-invalidates the PagingSource when insertAll touches
        // tx_display_cache — no need to reassign _txDataSource here.  Reassigning would cancel
        // the existing Pager and create a new one on every tx-batch, causing repeated submitData
        // calls and flickering. Only set RoomLive on the initial transition (handled above).
        log.info("updateWrappedListForTransactions: updated {} wrappers", affectedWrappers.size)

        // Attempt to resolve DashPay contacts for any newly-arrived transactions.
        // resolveAllContacts() is normally only triggered by rebuildWrappedList or a
        // contacts-change event, so a freshly sent/received contact payment would stay
        // labeled "Sent"/"Received" until the next rebuild without this call.
        if (unknownTxs.isNotEmpty()) {
            resolveAllContacts()
        }
    }

    /** Creates a plain single-tx anonymous [TransactionWrapper]. */
    private fun createSingleTxWrapper(tx: Transaction): TransactionWrapper = object : TransactionWrapper {
        override val id          = tx.txId.toString()
        override val transactions = hashMapOf(tx.txId to tx)
        override val groupDate   = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        override fun tryInclude(t: Transaction) = true
        override fun getValue(bag: TransactionBag) = tx.getValue(bag)
    }

    companion object {
        private const val BATCHING_PERIOD = 500L
        private const val DIRECTION_KEY = "tx_direction"
        private const val TIME_SKEW_TOLERANCE = 3600000L // seconds (1 hour)

        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }

    /** Returns the DAO filter flag for this direction. 0 means ALL (no WHERE clause). */
    private fun TxFilterType.toFilterFlag(): Int = when (this) {
        TxFilterType.SENT      -> TxDisplayCacheEntry.FLAG_SENT
        TxFilterType.RECEIVED  -> TxDisplayCacheEntry.FLAG_RECEIVED
        TxFilterType.GIFT_CARD -> TxDisplayCacheEntry.FLAG_GIFT_CARD
        TxFilterType.ALL       -> 0
    }

    /**
     * Computes the [TxDisplayCacheEntry.filterFlags] bitmask for [wrapper] based on the
     * direction of its transactions and whether it is a gift-card service transaction.
     */
    private fun computeFilterFlags(wrapper: TransactionWrapper): Int {
        val bag = walletData.transactionBag
        var flags = 0
        if (wrapper is CoinJoinMixingTxSet) {
            flags = TxDisplayCacheEntry.FLAG_COINJOIN
        } else {
            if (wrapper.transactions.values.any { TxDirectionFilter(TxFilterType.SENT, bag).matches(it) }) {
                flags = flags or TxDisplayCacheEntry.FLAG_SENT
            }
            if (wrapper.transactions.values.any { TxDirectionFilter(TxFilterType.RECEIVED, bag).matches(it) }) {
                flags = flags or TxDisplayCacheEntry.FLAG_RECEIVED
            }
            val firstTxId = wrapper.transactions.keys.first()
            if (ServiceName.isDashSpend(metadata[firstTxId]?.service)) {
                flags = flags or TxDisplayCacheEntry.FLAG_GIFT_CARD or TxDisplayCacheEntry.FLAG_SENT
            }
        }
        return flags
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
            _currentPagingSource.value?.invalidate()
            log.info("resolveAllContacts: resolved {} contacts for {} candidates",
                resolved.size, txsToResolve.size)

            // Upsert the affected cache rows with the now-known contact info so the
            // NEXT startup shows contact names/avatars immediately from the cache.
            val updatedEntries = wrappedTransactionList
                .filter { wrapper -> resolved.containsKey(wrapper.transactions.keys.first().toString()) }
                .map { wrapper ->
                    val txId = wrapper.transactions.keys.first()
                    val row = TransactionRowView.fromTransactionWrapper(
                        wrapper,
                        walletData.transactionBag,
                        Constants.CONTEXT,
                        contact = contactsByTxId[txId.toString()],
                        metadata = metadata[txId],
                        chainLockBlockHeight = chainLockBlockHeight
                    )
                    TxDisplayCacheEntry.fromTransactionRowView(row, walletApplication, computeFilterFlags(wrapper))
                }
            if (updatedEntries.isNotEmpty()) {
                txDisplayCacheDao.insertAll(updatedEntries)
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
     * Clears both the display cache and the group cache, resets [wrappedTransactionList],
     * and triggers a full [rebuildWrappedList] as if the app were launched for the first time.
     *
     * Intended for user-initiated "Refresh transaction list" actions only.
     */
    fun forceRebuildTransactionCache() {
        viewModelWorkerScope.launch {
            wrappedTransactionList = emptyList()
            txDisplayCacheDao.deleteAll()
            txGroupCacheDao.deleteAll()
            _txDataSource.value = TxDataSource.Empty
            val filter = TxDirectionFilter(_transactionsDirection.value, walletData.wallet!!)
            rebuildWrappedList(filter)
        }
    }

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
