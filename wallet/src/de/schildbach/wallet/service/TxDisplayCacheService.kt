/*
 * Copyright 2024 Dash Core Group.
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

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.database.entity.TxGroupCacheEntry
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.CutoverState
import de.schildbach.wallet.service.platform.sdk.dashjEngineMayStart
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.transactions.coinjoin.CoinJoinMixingTxSet
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxWrapperFactory
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.main.HistoryRowView
import de.schildbach.wallet.ui.transactions.TransactionRowView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.WalletEx
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet_test.R
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.ui.components.merchantNameBitmap
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import de.schildbach.wallet.transactions.TransactionUtils.isEntirelySelf
import de.schildbach.wallet.transactions.dashjTx
import de.schildbach.wallet.transactions.toTxInfo
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.transactions.TransactionWrapper
import de.schildbach.wallet.transactions.batchAndFilterUpdates
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSetFactory
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TxDisplayCacheService @Inject constructor(
    private val walletData: WalletData,
    private val walletApplication: WalletApplication,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val txGroupCacheDao: TxGroupCacheDao,
    private val metadataProvider: TransactionMetadataProvider,
    private val platformRepo: PlatformRepo,
    private val identityRepo: IdentityRepository,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val displayCacheRefreshBus: DisplayCacheRefreshBus,
    private val dashPayConfig: DashPayConfig
) {

    companion object {
        private const val BATCHING_PERIOD = 500L
        private val log = LoggerFactory.getLogger(TxDisplayCacheService::class.java)
    }

    // Single-threaded worker scope — all mutations to wrappedTransactionList, metadata,
    // contacts, contactsByTxId run here to avoid data races.
    @VisibleForTesting
    val serviceScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private val pagingConfig = PagingConfig(pageSize = 50, prefetchDistance = 20, enablePlaceholders = false)

    // In-memory sorted wrapped list
    @Volatile
    private var wrappedTransactionList: List<TransactionWrapper> = emptyList()

    private val _transactionsLoaded = MutableStateFlow(false)
    val transactionsLoaded: StateFlow<Boolean> = _transactionsLoaded.asStateFlow()

    private val _isBuildingCache = MutableStateFlow(false)
    val isBuildingCache: StateFlow<Boolean> = _isBuildingCache.asStateFlow()

    private val _currentPagingSource = MutableStateFlow<PagingSource<Int, TxDisplayCacheEntry>?>(null)

    private sealed class TxDataSource {
        object Empty : TxDataSource()
        class PrebuiltCache(val rows: List<HistoryRowView>) : TxDataSource()
        object RoomLive : TxDataSource()
    }
    private val _txDataSource = MutableStateFlow<TxDataSource>(TxDataSource.Empty)
    private val _liveFilterFlag = MutableStateFlow(0)

    // Internal filter driven by setFilter() calls from the ViewModel
    private val _currentFilter = MutableStateFlow(TxFilterType.ALL)

    @Volatile private var metadata: Map<TxId, PresentableTxMetadata> = mapOf()
    @Volatile private var contacts: Map<String, DashPayProfile> = mapOf()
    @Volatile private var contactsByTxId: Map<String, DashPayProfile> = mapOf()
    @Volatile private var minContactCreatedDate: LocalDate = LocalDate.MIN
    @Volatile private var chainLockBlockHeight: Int = 0
    private var wasReplaying: Boolean? = null

    private var crowdNodeWrapperFactory: FullCrowdNodeSignUpTxSetFactory? = null
    private var coinJoinWrapperFactory: CoinJoinTxWrapperFactory? = null

    /**
     * Pre-built rows for the fast startup phase (from Room display cache).
     * Set directly by the init coroutine so that the cacheAdapter always receives rows,
     * even if the wallet-ready flow wins the serviceScope race and transitions
     * _txDataSource to RoomLive before PrebuiltCache is set.
     */
    private val _cachedRows = MutableStateFlow<List<HistoryRowView>>(emptyList())
    val cachedRows: StateFlow<List<HistoryRowView>> = _cachedRows.asStateFlow()

    /**
     * Live PagingData stream. Switches from empty → Room-live after the first
     * rebuild or display-cache hydration. cachedIn() keeps pages across recompositions.
     */
    val transactions: Flow<PagingData<HistoryRowView>> = _txDataSource
        .flatMapLatest { source ->
            when (source) {
                is TxDataSource.Empty -> flowOf(PagingData.empty())
                is TxDataSource.PrebuiltCache -> flowOf(PagingData.empty())
                is TxDataSource.RoomLive -> {
                    Pager(
                        config = pagingConfig,
                        pagingSourceFactory = {
                            txDisplayCacheDao.pagingSource(_liveFilterFlag.value)
                                .also { _currentPagingSource.value = it }
                        }
                    ).flow.map { pagingData ->
                        pagingData
                            .map { entry ->
                                entry.toTransactionRowView(
                                    contactsByTxId[entry.rowId],
                                    iconBitmapForEntry(entry)
                                ) as HistoryRowView
                            }
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
        .cachedIn(serviceScope)

    init {
        // Load display cache for fast startup
        serviceScope.launch {
            val t0 = System.currentTimeMillis()
            val cachedRows = txDisplayCacheDao.getAll()
            log.info(
                "STARTUP tx_display_cache loaded {} rows in {}ms",
                cachedRows.size,
                System.currentTimeMillis() - t0
            )
            if (cachedRows.isNotEmpty()) {
                val contacts = contactsByTxId
                val historyRows = ArrayList<HistoryRowView>(cachedRows.size + 32)
                var prevDate: LocalDate? = null
                for (entry in cachedRows) {
                    val txRow = entry.toTransactionRowView(
                        contacts[entry.rowId],
                        iconBitmapForEntryCold(entry)
                    )
                    val date = Instant.ofEpochMilli(txRow.time)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    if (date != prevDate) {
                        historyRows.add(HistoryRowView(null, date))
                        prevDate = date
                    }
                    historyRows.add(txRow)
                }
                // Always populate the cache adapter, regardless of which coroutine won
                // the serviceScope race (_txDataSource may already be RoomLive if
                // getCount() completed before getAll()).
                _cachedRows.value = historyRows
                if (_txDataSource.value is TxDataSource.Empty) {
                    _txDataSource.value = TxDataSource.PrebuiltCache(historyRows)
                    _transactionsLoaded.value = true
                }
            }
        }

        combine(_currentFilter, walletData.observeWallet()) { direction, wallet -> direction to wallet }
            .flatMapLatest { (direction, wallet) ->
                if (wallet == null) return@flatMapLatest emptyFlow()
                val filter = TxDirectionFilter(direction, wallet)
                if (wrappedTransactionList.isEmpty()) {
                    if (txDisplayCacheDao.getCount() > 0) {
                        _liveFilterFlag.value = filter.direction.toFilterFlag()
                        _txDataSource.value = TxDataSource.RoomLive
                        _transactionsLoaded.value = true
                        initializeFactoriesFromCache()
                    } else {
                        rebuildWrappedList(filter)
                    }
                } else {
                    log.info("direction changed to {} — switching filter flag only", direction)
                    _liveFilterFlag.value = filter.direction.toFilterFlag()
                    _txDataSource.value = TxDataSource.RoomLive // force new Pager with updated filter
                    _currentPagingSource.value?.invalidate()
                }
                val allFilter = TxDirectionFilter(TxFilterType.ALL, wallet)
                walletData.observeTransactions(true, allFilter)
                    .batchAndFilterUpdates(BATCHING_PERIOD)
                    .onEach { txs -> updateWrappedListForTransactions(txs) }
            }
            .catch { e -> log.error("transactionsDirection flow error", e) }
            .launchIn(serviceScope)

        metadataProvider.observePresentableMetadata()
            .onEach { newMetadata ->
                val oldMetadata = this.metadata
                this.metadata = newMetadata

                val changedIds = buildSet<String> {
                    newMetadata.forEach { (id, meta) -> if (meta != oldMetadata[id]) add(id.toString()) }
                    oldMetadata.forEach { (id, _) -> if (id !in newMetadata) add(id.toString()) }
                }

                if (changedIds.isEmpty()) {
                    // PresentableTxMetadata.equals ignores the decoded icon bitmap, so a
                    // metadata emission that only adds/changes a merchant logo (a later
                    // observeBitmaps emission) produces no changedIds. Re-map the live pager
                    // so cached gift card rows pick up the now-available bitmap from the
                    // refreshed in-memory metadata, without rewriting any rows.
                    _currentPagingSource.value?.invalidate()
                    return@onEach
                }

                val inMemoryWrappers = wrappedTransactionList.filter { wrapper ->
                    wrapper.transactions.keys.any { it in changedIds }
                }
                val inMemoryTxIds = inMemoryWrappers.flatMap { it.transactions.keys }.toSet()
                val missingTxIds = changedIds.filter { it !in inMemoryTxIds }
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
                        val txId = wrapper.transactions.keys.firstOrNull { it in changedIds }
                            ?: wrapper.transactions.keys.first()
                        val row = TransactionRowView.fromTransactionWrapper(
                            wrapper,
                            walletData.transactionBag,
                            Constants.CONTEXT,
                            contact = contactsByTxId[txId],
                            metadata = newMetadata[TxId.wrap(txId)],
                            chainLockBlockHeight = chainLockBlockHeight
                        )
                        TxDisplayCacheEntry.fromTransactionRowView(
                            row,
                            walletApplication,
                            computeFilterFlags(wrapper),
                            newMetadata[TxId.wrap(txId)]?.customIconId?.toString()
                        )
                    }
                    val rowIds = newEntries.map { it.rowId }
                    val existingByRowId = txDisplayCacheDao.getEntriesByIds(rowIds).associateBy { it.rowId }
                    val entries = newEntries.map { entry ->
                        val existing = existingByRowId[entry.rowId] ?: return@map entry
                        var result = entry
                        if (existing.service != null && result.service == null) {
                            result = result.copy(
                                service      = existing.service,
                                iconType     = existing.iconType,
                                iconBgType   = existing.iconBgType,
                                customIconId = result.customIconId ?: existing.customIconId
                            )
                        }
                        // Post-cutover preserve-guard: this rebuild comes from the
                        // dashj wrapper, which for an SDK-only tx reports value=0 /
                        // rate=null. A memo/tax edit must NOT clobber a value/rate
                        // that CutoverUiDataService already re-stamped onto the row,
                        // so keep the existing non-degenerate value/rate (mirrors the
                        // service-preservation guard above).
                        if (result.valueSatoshis == 0L && existing.valueSatoshis != 0L) {
                            result = result.copy(valueSatoshis = existing.valueSatoshis)
                        }
                        if (result.exchangeRateFiatCode == null &&
                            result.exchangeRateFiatValue == null &&
                            existing.exchangeRateFiatCode != null
                        ) {
                            result = result.copy(
                                exchangeRateFiatCode  = existing.exchangeRateFiatCode,
                                exchangeRateFiatValue = existing.exchangeRateFiatValue
                            )
                        }
                        // Post-cutover preserve-guard (direction/amount/status/icon): an
                        // SDK-stamped row carries the engine + contact correction that the dashj
                        // wrapper cannot reproduce — an SDK contact send surfaces only its +change,
                        // so this rebuild would revert the row to a "Received" misread (wrong icon,
                        // title, direction and status). A memo/tax-category edit must never change a
                        // tx's direction/amount/status, so when the existing row was SDK-stamped
                        // (it carries a contact identity, or the dashj rebuild degenerated to
                        // value 0 while the cached row holds a real value) preserve its
                        // value/icon/title/status/filter bucket across the rebuild. For a
                        // pre-cutover contact tx the existing row was built from the same dashj
                        // computation, so this copy is a no-op — behavior is byte-for-byte unchanged.
                        // The SDK-authority register covers NON-contact rows (a plain
                        // SDK-authored send has no contact identity and its dashj
                        // rebuild is not always degenerate), closing the hole that let
                        // a memo/tax edit revert a corrected plain send.
                        val existingIsSdkStamped = existing.contactUserId != null ||
                            displayCacheRefreshBus.isSdkAuthoritative(entry.rowId) ||
                            (entry.valueSatoshis == 0L && existing.valueSatoshis != 0L)
                        if (existingIsSdkStamped) {
                            result = result.copy(
                                valueSatoshis = existing.valueSatoshis,
                                iconType      = existing.iconType,
                                iconBgType    = existing.iconBgType,
                                title         = existing.title,
                                statusText    = existing.statusText,
                                filterFlags   = existing.filterFlags
                            )
                        }
                        result
                    }
                    txDisplayCacheDao.insertAll(entries)
                }
            }
            .catch { e -> log.error("metadata flow error", e) }
            .launchIn(serviceScope)

        identityRepo.observeContacts(
            "",
            de.schildbach.wallet.data.UsernameSortOrderBy.LAST_ACTIVITY,
            false
        ).distinctUntilChanged()
            .onEach { contacts ->
                this.minContactCreatedDate = contacts.minOfOrNull { it.dashPayProfile.createdAt }?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: LocalDate.MIN
                val contactsByIdentity = contacts.associate { it.dashPayProfile.userId to it.dashPayProfile }
                this.contacts = contactsByIdentity
                this.contactsByTxId = mapOf()
                resolveAllContacts()
            }
            .catch { e -> log.error("contacts flow error", e) }
            .launchIn(serviceScope)

        walletData.observeWalletReset()
            .onEach {
                wrappedTransactionList = emptyList()
                contactsByTxId = mapOf()
                _cachedRows.value = emptyList()
                txDisplayCacheDao.deleteAll()
                txGroupCacheDao.deleteAll()
                walletData.wallet?.let { wallet ->
                    coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
                    crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkId)
                }
                _currentPagingSource.value?.invalidate()
            }
            .catch { e -> log.error("wallet reset flow error", e) }
            .launchIn(serviceScope)

        blockchainStateProvider.observeState()
            .onEach { state ->
                if (state != null) {
                    chainLockBlockHeight = state.chainlockHeight
                    val prev = wasReplaying
                    wasReplaying = state.replaying
                    when {
                        prev == true && !state.replaying -> {
                            // Sync just completed in this session — verify cache is complete.
                            rebuildIfCacheIncomplete()
                        }
                        prev == null && !state.replaying -> {
                            // First observation and blockchain is already synced. A previous
                            // session may have been killed mid-replay (e.g. after a rescan),
                            // leaving the cache partial. Check now rather than waiting for a
                            // replaying→false transition that will never come.
                            rebuildIfCacheIncomplete()
                        }
                    }
                }
            }
            .catch { e -> log.error("blockchain state flow error (cache service)", e) }
            .launchIn(serviceScope)

        // Belt-and-suspenders home-list refresh: CutoverUiDataService signals the bus
        // immediately after every display-cache write (fresh SDK receive, direction/amount
        // correction, or a pending→confirmed IS-lock flip). Room's InvalidationTracker is
        // supposed to re-fire the live PagingSource on those upserts but on-device it can
        // miss the change, leaving a stale "Sending"/"Processing" row for minutes. Force the
        // active PagingSource to re-query on every signal — equivalent to adapter.refresh(),
        // and no more disruptive to scroll than any normal data change. Pre-cutover nothing
        // writes the cache, so the bus never fires and this is inert.
        displayCacheRefreshBus.changes
            .onEach { _currentPagingSource.value?.invalidate() }
            .catch { e -> log.error("display cache refresh bus flow error", e) }
            .launchIn(serviceScope)
    }

    /**
     * Called by the ViewModel when the user changes the transaction filter tab.
     * Primes the initial filter if not yet set; subsequent calls update in-place.
     */
    fun setFilter(direction: TxFilterType) {
        _currentFilter.value = direction
    }

    /**
     * Synchronous in-memory lookup — returns null if [rowId] is not yet loaded.
     * Use [loadGroupWrapper] for a full lookup that falls through to the group cache.
     */
    fun getTransactionWrapper(rowId: String): TransactionWrapper? =
        wrappedTransactionList.find { it.id == rowId }

    /**
     * Returns the in-memory wrapper for [rowId], or loads it from the group cache on demand.
     */
    suspend fun loadGroupWrapper(rowId: String): TransactionWrapper? {
        wrappedTransactionList.find { it.id == rowId }?.let { return it }
        val firstEntry = txGroupCacheDao.getGroupEntries(rowId).firstOrNull() ?: return null
        return loadWrapperOnDemand(rowId, firstEntry.wrapperType)
    }

    /**
     * Called when blockchain sync transitions from replaying to synced.
     *
     * Two independent inconsistencies can leave the display stale:
     *   1. **Missing group-cache entries** — the group cache has fewer individual tx rows
     *      than the wallet (`walletTxCount > cachedTxCount`).  This happens when the
     *      previous session was killed before its replay finished and the incremental
     *      update path (`updateWrappedListForTransactions`) never saw those txs.
     *   2. **Display / group-cache mismatch** — the group cache was updated incrementally
     *      (all tx entries are present) but the display cache was not fully written, so it
     *      has fewer rows than there are distinct groups.  This can occur when all replayed
     *      txs merged into existing CoinJoin date-groups (REPLACE instead of INSERT in
     *      `tx_display_cache`) while the underlying tx membership changed.
     *
     * Either condition triggers a full rebuild.
     */
    private fun rebuildIfCacheIncomplete() {
        serviceScope.launch {
            // If the wallet isn't loaded yet (blockchain state can fire before the wallet
            // is restored from disk), suspend until it becomes available.
            val wallet = walletData.wallet
                ?: walletData.observeWallet().filterNotNull().first()
            val walletTxCount = wallet.getTransactionCount(true)
            val cachedTxCount = txGroupCacheDao.getTotalTxCount()
            val groupCount = txGroupCacheDao.getGroupCount()
            val displayRowCount = txDisplayCacheDao.getCount()

            // Fresh install / post-wipe: nothing in the wallet and nothing cached.
            if (walletTxCount == 0 && cachedTxCount == 0 && displayRowCount == 0) {
                return@launch
            }
            
            val txsMissing = walletTxCount > cachedTxCount
            val displayIncomplete = displayRowCount < groupCount
            val needsRebuild = txsMissing || displayIncomplete

            log.info(
                "Sync complete: wallet={} txs | group cache={} txs/{} groups | display={} rows — {}",
                walletTxCount, cachedTxCount, groupCount, displayRowCount,
                if (needsRebuild)
                    "rebuilding (txsMissing=$txsMissing, displayIncomplete=$displayIncomplete)"
                else
                    "cache is complete"
            )
            if (needsRebuild) {
                forceRebuildTransactionCache()
            }
        }
    }

    /**
     * The persisted cutover verdict, through the SAME predicate the send path
     * and [de.schildbach.wallet.service.platform.sdk.CutoverUiDataService]
     * evaluate. Reads the store rather than the live UI gate so the answer is
     * correct before the SDK pipelines have started.
     *
     * A read failure reports COMMITTED — the direction that refuses a
     * destructive dashj rebuild it cannot prove is safe. The refusal only
     * bites when the dashj wallet is also empty, where a rebuild has nothing
     * to rebuild anyway.
     */
    private suspend fun cutoverCommittedOrUnknown(): Boolean = try {
        !dashjEngineMayStart(CutoverState.fromStored(dashPayConfig.get(DashPayConfig.CUTOVER_STATE)))
    } catch (e: Exception) {
        log.warn("could not read the cutover state; treating it as committed", e)
        true
    }

    /**
     * Wipes both caches and rebuilds them from the dashj wallet.
     *
     * REFUSES to run post-cutover on a held dashj wallet — see
     * [dashjRebuildWouldEraseHistory]. Reachable from the home screen (a long
     * press on the History title offers "refresh"), so the refusal has to be
     * structural, not a convention.
     */
    fun forceRebuildTransactionCache() {
        serviceScope.launch {
            val wallet = walletData.wallet ?: return@launch
            val dashjTxCount = wallet.getTransactionCount(true)
            if (dashjRebuildWouldEraseHistory(cutoverCommittedOrUnknown(), dashjTxCount)) {
                log.error(
                    "REFUSING to rebuild the transaction cache: the cutover is committed and the " +
                        "dashj wallet holds 0 transactions, so rebuilding from it would wipe all " +
                        "{} display rows / {} group rows and leave the history permanently empty " +
                        "(the SDK owns the transactions now, and nothing re-populates a wiped " +
                        "dashj-sourced cache)",
                    txDisplayCacheDao.getCount(), txGroupCacheDao.getTotalTxCount()
                )
                return@launch
            }
            txDisplayCacheDao.deleteAll()
            txGroupCacheDao.deleteAll()
            wrappedTransactionList = emptyList()
            _txDataSource.value = TxDataSource.Empty
            val filter = TxDirectionFilter(_currentFilter.value, wallet)
            rebuildWrappedList(filter)
        }
    }

    /**
     * Immediately clears the in-memory pre-built rows so that a new Activity started
     * after a blockchain reset does not display stale cached data.  Call this before
     * launching the new Activity (e.g. inside [WalletApplication.resetBlockchain]).
     * No Room I/O — safe to call from any thread including the main thread.
     */
    fun clearInMemoryCache() {
        _cachedRows.value = emptyList()
    }

    /** clear database tables during a wipe wallet or rescan operation */
    suspend fun clearDatabase() {
        txDisplayCacheDao.deleteAll()
        txGroupCacheDao.deleteAll()
        wrappedTransactionList = emptyList()
        _cachedRows.value = emptyList()
        // Invalidate the current PagingSource so it re-queries the now-empty table.
        // Do NOT set _txDataSource = Empty: that would stop the Pager, preventing
        // recovery as transactions are re-added during a rescan.
        _currentPagingSource.value?.invalidate()
    }

    private suspend fun rebuildWrappedList(filter: TxDirectionFilter) {
        _isBuildingCache.value = true
        try {
            walletData.wallet?.let { wallet ->
                val t0 = System.currentTimeMillis()
                coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
                crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkId)

                val rawCount = wallet.getTransactions(true).size
                val t1 = System.currentTimeMillis()

                val cnFactory = crowdNodeWrapperFactory ?: return
                val cjFactory = coinJoinWrapperFactory ?: return
                val wrapped = walletData.wrapAllTransactions(cnFactory, cjFactory)
                val t2 = System.currentTimeMillis()

                wrappedTransactionList = wrapped.sortedByDescending { it.groupDate }
                val t3 = System.currentTimeMillis()

                log.info(
                    "rebuildWrappedList: {} raw txs → {} wrappers → {} sorted | " +
                    "getTransactions={}ms wrapAll={}ms sort={}ms total={}ms",
                    rawCount, wrapped.size, wrappedTransactionList.size,
                    t1 - t0, t2 - t1, t3 - t2, t3 - t0
                )

                persistGroupCache(wrapped)
                updateDisplayCache(wrapped.toList(), filter.direction.toFilterFlag())

                _isBuildingCache.value = false
                _transactionsLoaded.value = true

                log.info("STARTUP rebuildWrappedList DONE (_transactionsLoaded=true) at {}", System.currentTimeMillis())
                serviceScope.launch { resolveAllContacts() }
            }
        } finally {
            _isBuildingCache.value = false
        }
    }

    private suspend fun updateDisplayCache(wrappers: List<TransactionWrapper>, filterFlag: Int) {
        val t0 = System.currentTimeMillis()

        fun renderEntry(wrapper: TransactionWrapper): TxDisplayCacheEntry {
            val txId = wrapper.transactions.keys.first()
            val row = TransactionRowView.fromTransactionWrapper(
                wrapper,
                walletData.transactionBag,
                Constants.CONTEXT,
                contact = contactsByTxId[txId],
                metadata = metadata[TxId.wrap(txId)],
                chainLockBlockHeight = chainLockBlockHeight
            )
            return TxDisplayCacheEntry.fromTransactionRowView(
                row,
                walletApplication,
                computeFilterFlags(wrapper),
                metadata[TxId.wrap(txId)]?.customIconId?.toString()
            )
        }

        val allEntries = wrappers.map { renderEntry(it) }
        // Set the filter flag BEFORE writing to Room so that when Room's invalidation
        // tracker fires the pagingSourceFactory callback (which reads _liveFilterFlag),
        // it already sees the correct flag rather than the stale previous value.
        _liveFilterFlag.value = filterFlag
        txDisplayCacheDao.replaceAll(allEntries)
        log.info("updateDisplayCache: {} rows in {}ms", allEntries.size, System.currentTimeMillis() - t0)
        _txDataSource.value = TxDataSource.RoomLive
    }

    private suspend fun persistGroupCache(wrappers: Collection<TransactionWrapper>) {
        val entries = wrappers.flatMap { wrapper ->
            val type = when (wrapper) {
                is CoinJoinMixingTxSet     -> TxGroupCacheEntry.TYPE_COINJOIN
                is FullCrowdNodeSignUpTxSet -> TxGroupCacheEntry.TYPE_CROWDNODE
                else                       -> TxGroupCacheEntry.TYPE_SINGLE
            }
            wrapper.transactions.values
                .sortedBy { it.updateTimeMillis }
                .mapIndexed { index, tx ->
                    TxGroupCacheEntry(
                        groupId     = wrapper.id,
                        txId        = tx.txId,
                        wrapperType = type,
                        groupDate   = wrapper.groupDate.toString(),
                        sortOrder   = index
                    )
                }
        }
        txGroupCacheDao.replaceAll(entries)
    }

    private suspend fun initializeFactoriesFromCache() {
        val wallet = walletData.wallet ?: return
        val t0 = System.currentTimeMillis()
        val cjFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
        val cnFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkId)
        coinJoinWrapperFactory = cjFactory
        crowdNodeWrapperFactory = cnFactory

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
                    txs.forEach { cjFactory.tryInclude(it.toTxInfo(wallet, walletData.networkParameters)) }
                    cjFactory.wrappers.find { it.id == groupId }
                }
                TxGroupCacheEntry.TYPE_CROWDNODE -> {
                    txs.forEach { cnFactory.tryInclude(it.toTxInfo(wallet, walletData.networkParameters)) }
                    cnFactory.wrappers.find { it.id == groupId }
                }
                else -> null
            }
            wrapper?.let { activeWrappers.add(it) }
        }

        wrappedTransactionList = activeWrappers.sortedByDescending { it.groupDate }
        log.info("initializeFactoriesFromCache: {} active groups loaded in {}ms",
            byGroup.size, System.currentTimeMillis() - t0)
    }

    private suspend fun loadWrapperOnDemand(groupId: String, wrapperType: String): TransactionWrapper? {
        val wallet = walletData.wallet ?: return null
        // Lazily initialize factories if they haven't been set yet (e.g. metadata flow fires
        // before rebuildWrappedList / initializeFactoriesFromCache has run).
        if (coinJoinWrapperFactory == null) {
            coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
            crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkId)
        }
        val cjFactory = coinJoinWrapperFactory ?: return null
        val cnFactory = crowdNodeWrapperFactory ?: return null

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
                txs.forEach { cjFactory.tryInclude(it.toTxInfo(wallet, walletData.networkParameters)) }
                cjFactory.wrappers.find { it.id == groupId }
            }
            TxGroupCacheEntry.TYPE_CROWDNODE -> {
                txs.forEach { cnFactory.tryInclude(it.toTxInfo(wallet, walletData.networkParameters)) }
                cnFactory.wrappers.find { it.id == groupId }
            }
            else -> txs.firstOrNull()?.let { createSingleTxWrapper(it) }
        } ?: return null

        if (wrappedTransactionList.none { it.id == wrapper.id }) {
            wrappedTransactionList = (wrappedTransactionList + wrapper)
                .sortedByDescending { it.groupDate }
        }
        return wrapper
    }

    private suspend fun updateWrappedListForTransactions(txs: List<Transaction>) {
        val bag = walletData.transactionBag
        val params = walletData.networkParameters
        val txIdToWrapper = HashMap<String, TransactionWrapper>(wrappedTransactionList.size * 4)
        wrappedTransactionList.forEach { wrapper ->
            wrapper.transactions.keys.forEach { txId ->
                txIdToWrapper[txId] = wrapper
            }
        }

        val mutableList = wrappedTransactionList.toMutableList()
        val affectedWrappers = mutableSetOf<TransactionWrapper>()
        val unknownTxs = mutableListOf<Transaction>()

        for (tx in txs) {
            val existing = txIdToWrapper[tx.txId.toString()]
            if (existing != null) {
                existing.transactions[tx.txId.toString()] = tx.toTxInfo(bag, params)
                affectedWrappers.add(existing)
            } else {
                unknownTxs.add(tx)
            }
        }

        if (unknownTxs.isNotEmpty()) {
            val unknownKeys = unknownTxs.map { it.txId.toString() }
            val cachedByTxId = txGroupCacheDao.getGroupsForTxIds(unknownKeys).associateBy { it.txId }
            val loadedById = mutableList.associateByTo(HashMap()) { it.id }

            for (tx in unknownTxs) {
                val txKey = tx.txId.toString()
                val cacheEntry = cachedByTxId[txKey]

                if (cacheEntry != null) {
                    val wrapper = loadedById[cacheEntry.groupId]
                        ?: loadWrapperOnDemand(cacheEntry.groupId, cacheEntry.wrapperType)
                            ?.also { loadedById[it.id] = it }
                    if (wrapper != null) {
                        wrapper.transactions[tx.txId.toString()] = tx.toTxInfo(bag, params)
                        affectedWrappers.add(wrapper)
                        if (mutableList.none { it.id == wrapper.id }) {
                            mutableList.add(wrapper)
                        }
                        continue
                    }
                }

                var added = false

                val txInfo = tx.toTxInfo(bag, params)
                val (cjIncluded, cjWrapper) = coinJoinWrapperFactory?.tryInclude(txInfo) ?: (false to null)
                if (cjIncluded && cjWrapper != null) {
                    if (mutableList.none { it.id == cjWrapper.id }) {
                        mutableList.add(cjWrapper)
                    }
                    affectedWrappers.add(cjWrapper)
                    added = true
                }

                if (!added) {
                    val (cnIncluded, cnWrapper) = crowdNodeWrapperFactory?.tryInclude(txInfo) ?: (false to null)
                    if (cnIncluded && cnWrapper != null) {
                        if (mutableList.none { it.id == cnWrapper.id }) {
                            mutableList.add(cnWrapper)
                        }
                        affectedWrappers.add(cnWrapper)
                        added = true
                    }
                }

                if (!added) {
                    val wrapper = createSingleTxWrapper(tx)
                    affectedWrappers.add(wrapper)
                    mutableList.add(wrapper)
                }
            }
        }

        if (mutableList.size != wrappedTransactionList.size) {
            mutableList.sortByDescending { it.groupDate }
        }
        wrappedTransactionList = mutableList

        if (affectedWrappers.isEmpty()) return

        val displayEntries = affectedWrappers.map { wrapper ->
            val txId = wrapper.transactions.keys.first()
            val row = TransactionRowView.fromTransactionWrapper(
                wrapper, walletData.transactionBag, Constants.CONTEXT,
                contact = contactsByTxId[txId],
                metadata = metadata[TxId.wrap(txId)],
                chainLockBlockHeight = chainLockBlockHeight
            )
            TxDisplayCacheEntry.fromTransactionRowView(
                row,
                walletApplication,
                computeFilterFlags(wrapper),
                metadata[TxId.wrap(txId)]?.customIconId?.toString()
            )
        }
        if (displayEntries.isNotEmpty()) {
            val beforeCount = txDisplayCacheDao.getCount()
            // Never regress an SDK-stamped row: mid-rescan the dashj wrapper computes a
            // "Received +change"/no-contact misread for a contact send the SDK already
            // corrected — merge so contact identity and direction/shape are preserved.
            txDisplayCacheDao.insertAll(mergeAllPreservingSdkStamped(displayEntries))
            val afterCount = txDisplayCacheDao.getCount()
            if (afterCount != beforeCount) {
                log.info(
                    "updateWrappedList: {} batch txs → {} affected wrappers | display {} → {} rows",
                    txs.size, affectedWrappers.size, beforeCount, afterCount
                )
            }
        }

        val groupEntries = affectedWrappers.flatMap { wrapper ->
            val type = when (wrapper) {
                is CoinJoinMixingTxSet     -> TxGroupCacheEntry.TYPE_COINJOIN
                is FullCrowdNodeSignUpTxSet -> TxGroupCacheEntry.TYPE_CROWDNODE
                else                       -> TxGroupCacheEntry.TYPE_SINGLE
            }
            wrapper.transactions.values
                .sortedBy { it.updateTimeMillis }
                .mapIndexed { index, tx ->
                    TxGroupCacheEntry(
                        groupId     = wrapper.id,
                        txId        = tx.txId,
                        wrapperType = type,
                        groupDate   = wrapper.groupDate.toString(),
                        sortOrder   = index
                    )
                }
        }
        txGroupCacheDao.insertAll(groupEntries)

        if (unknownTxs.isNotEmpty()) {
            resolveContactsForTransactions(unknownTxs, affectedWrappers)
        }
    }

    private suspend fun resolveAllContacts() {
        if (contacts.isEmpty()) return
        if (!identityRepo.hasBlockchainIdentity) return

        val txsToResolve = wrappedTransactionList
            .map { it.transactions.values.first() }
            .filter { tx ->
                !tx.isEntirelySelf &&
                    tx.groupDate >= minContactCreatedDate &&
                    contactsByTxId[tx.txId] == null
            }

        if (txsToResolve.isEmpty()) return

        // Snapshot contacts before entering the parallel IO section so that IO threads
        // read an immutable map reference rather than the mutable serviceScope field.
        val contactsSnapshot = contacts
        val resolved = coroutineScope {
            txsToResolve
                .map { tx ->
                    async(Dispatchers.IO) {
                        try {
                            identityRepo.blockchainIdentity?.getContactForTransaction(tx.dashjTx)?.let { id ->
                                contactsSnapshot[id]?.let { profile -> tx.txId to profile }
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
            log.info("resolveAllContacts: resolved {} contacts for {} candidates", resolved.size, txsToResolve.size)

            val updatedEntries = wrappedTransactionList
                .filter { wrapper -> resolved.containsKey(wrapper.transactions.keys.first().toString()) }
                .map { wrapper ->
                    val txId = wrapper.transactions.keys.first()
                    val row = TransactionRowView.fromTransactionWrapper(
                        wrapper,
                        walletData.transactionBag,
                        Constants.CONTEXT,
                        contact = contactsByTxId[txId],
                        metadata = metadata[TxId.wrap(txId)],
                        chainLockBlockHeight = chainLockBlockHeight
                    )
                    TxDisplayCacheEntry.fromTransactionRowView(
                        row,
                        walletApplication,
                        computeFilterFlags(wrapper),
                        metadata[TxId.wrap(txId)]?.customIconId?.toString()
                    )
                }
            if (updatedEntries.isNotEmpty()) {
                // Attach the freshly-resolved contact but never regress the SDK-stamped
                // value/direction/status of an already-corrected row (the dashj wrapper
                // misreads an SDK contact send as "Received +change").
                txDisplayCacheDao.insertAll(mergeAllPreservingSdkStamped(updatedEntries))
            }
        }
    }

    private suspend fun resolveContactsForTransactions(
        newTxs: List<Transaction>,
        affectedWrappers: Set<TransactionWrapper>
    ) {
        if (contacts.isEmpty() || !identityRepo.hasBlockchainIdentity) return

        val txsToResolve = newTxs.filter { tx ->
            !tx.isEntirelySelf(walletData.transactionBag) &&
                tx.updateTime
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate() >= minContactCreatedDate &&
                contactsByTxId[tx.txId.toString()] == null
        }
        if (txsToResolve.isEmpty()) return

        // Snapshot contacts before entering the parallel IO section so that IO threads
        // read an immutable map reference rather than the mutable serviceScope field.
        val contactsSnapshot = contacts
        val resolved = coroutineScope {
            txsToResolve
                .map { tx ->
                    async(Dispatchers.IO) {
                        try {
                            identityRepo.blockchainIdentity?.getContactForTransaction(tx)
                                ?.let { id ->
                                    contactsSnapshot[id]?.let { profile ->
                                        tx.txId.toString() to profile
                                    }
                                }
                        } catch (e: Exception) {
                            log.warn(
                                "failed to resolve contact for new tx {}: {}",
                                tx.txId,
                                e.message
                            )
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
        if (resolved.isEmpty()) return

        contactsByTxId = contactsByTxId + resolved
        _currentPagingSource.value?.invalidate()

        val updatedEntries = affectedWrappers
            .filter { wrapper ->
                resolved.containsKey(wrapper.transactions.keys.first().toString())
            }
            .map { wrapper ->
                val txId = wrapper.transactions.keys.first()
                val row = TransactionRowView.fromTransactionWrapper(
                    wrapper,
                    walletData.transactionBag,
                    Constants.CONTEXT,
                    contact = contactsByTxId[txId],
                    metadata = metadata[TxId.wrap(txId)],
                    chainLockBlockHeight = chainLockBlockHeight
                )
                TxDisplayCacheEntry.fromTransactionRowView(
                    row,
                    walletApplication,
                    computeFilterFlags(wrapper),
                    metadata[TxId.wrap(txId)]?.customIconId?.toString()
                )
            }
        if (updatedEntries.isNotEmpty()) {
            // Same preserve-merge as resolveAllContacts: attach the contact, keep the
            // SDK-stamped shape.
            txDisplayCacheDao.insertAll(mergeAllPreservingSdkStamped(updatedEntries))
        }
    }

    private fun createSingleTxWrapper(tx: Transaction): TransactionWrapper = object : TransactionWrapper {
        private val txInfo = tx.toTxInfo(walletData.transactionBag, walletData.networkParameters)
        override val id           = tx.txId.toString()
        override val transactions = hashMapOf(txInfo.txId to txInfo)
        override val groupDate    = txInfo.groupDate
        override fun tryInclude(tx: org.dash.wallet.common.transactions.TxInfo) = tx.txId == txInfo.txId
        override fun getValue() = org.dash.wallet.common.money.Dash(txInfo.netValueDuffs)
    }

    /**
     * Non-blocking lookup of the merchant/service icon bitmap for a cached row, using the
     * in-memory [metadata] map (already-decoded bitmaps). Returns null for rows without a
     * custom icon — the common case — so the static [iconType] drawable is used instead.
     * Safe to call from the Paging `map` lambda (no suspension, no disk I/O).
     *
     * Deliberately does NOT gate on [TxDisplayCacheEntry.customIconId]: rows cached by a
     * prior app version (or before the 19→20 migration) have a null column, but the bitmap
     * is still available here from the live metadata map, so existing gift card rows show
     * their merchant logo immediately on the live path without waiting for a rewrite or
     * rebuild. `metadata.icon` is only non-null for gift cards, so grouped/plain rows stay
     * unaffected; group rows whose rowId is not a tx hash simply fail the wrap and return null.
     */
    private fun iconBitmapForEntry(entry: TxDisplayCacheEntry): Bitmap? {
        val txId = try {
            TxId.wrap(entry.rowId)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val meta = metadata[txId] ?: return null
        // Real merchant logo, when present.
        meta.icon?.let { return it }
        // DashSpend gift card with no merchant logo → generate a full-name icon from the
        // merchant name (meta.title) instead of falling back to the static gift card icon.
        if (ServiceName.isDashSpend(meta.service) && !meta.title.isNullOrBlank()) {
            return generatedMerchantIcon(meta.title!!)
        }
        return null
    }

    /** Cache of generated full-name merchant icons, keyed by merchant name. */
    private val generatedMerchantIcons = ConcurrentHashMap<String, Bitmap>()

    /**
     * Returns a generated full-name icon for [merchantName], created once and cached.
     * Used for gift card transactions whose merchant has no logo.
     */
    private fun generatedMerchantIcon(merchantName: String): Bitmap =
        generatedMerchantIcons.computeIfAbsent(merchantName) {
            val sizePx = walletApplication.resources.getDimensionPixelSize(R.dimen.transaction_icon_size)
            merchantNameBitmap(walletApplication, it, sizePx)
        }

    /**
     * Cold-start variant: prefers the in-memory bitmap, then falls back to reading the
     * `icon_bitmaps` table directly (the metadata flow may not have populated [metadata]
     * yet when the fast-startup cache is first rendered).
     */
    private suspend fun iconBitmapForEntryCold(entry: TxDisplayCacheEntry): Bitmap? {
        iconBitmapForEntry(entry)?.let { return it }
        val iconId = entry.customIconId ?: return null
        return try {
            metadataProvider.getIcon(TxId.wrap(iconId))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Merge a dashj-rebuilt display [entry] over the [existing] cached row so the
     * rebuild can never REGRESS SDK-stamped state (mirrors the metadata-flow
     * preserve-guard, factored for the live-tx and contact-resolution writers).
     *
     * Post-cutover / post-restore, [CutoverUiDataService] corrects contact rows to
     * their authoritative shape (engine signed net → SENT −0.1 with the contact
     * identity), but the dashj-side writers here rebuild the same rowIds from the
     * dashj wrapper, which mid-rescan (inputs not yet connected) or for an
     * SDK-only tx computes a "Received +change" misread with no contact — on
     * device this flip-flopped a corrected SENT contact row back to a bare green
     * RECEIVED arrow until the next SDK pass (≤60s). Guards, all idempotent:
     *  - an existing service classification is kept (same as the metadata guard);
     *  - a degenerate rebuild (value 0 while the cached row holds a real value)
     *    keeps the cached value, and a null rate never clears a stamped rate;
     *  - contact attribution is never DROPPED: a rebuild without a contact keeps
     *    the cached contact columns;
     *  - the display SHAPE (value/icon/title/status/filter bucket) of a row
     *    carrying a contact identity is frozen ENTIRELY against this rebuild
     *    (not just on a direction flip: on-device a same-direction rebuild
     *    rewrote a contact send's −0.4 to −260, dashj's fee-only misread of a
     *    friendship payment). Only same-direction status PROGRESS passes
     *    through ("Sending"→"Sent", clearing a stale secondary status);
     *    degenerate rebuilds freeze the same way. Non-contact, non-degenerate
     *    rows pass through unchanged, so pre-cutover behaviour is untouched.
     */
    private fun mergePreservingSdkStamped(
        entry: TxDisplayCacheEntry,
        existing: TxDisplayCacheEntry?
    ): TxDisplayCacheEntry = mergeDisplayEntryPreservingSdkStamped(
        entry = entry,
        existing = existing,
        sendingTitle = walletApplication.getString(R.string.transaction_row_status_sending),
        sentTitle = walletApplication.getString(R.string.transaction_row_status_sent),
        // NON-contact SDK-stamped rows: the row carries no contact identity to
        // recognise it by, so the SDK sync pipeline registers every rowId it planned
        // or verified ([DisplayCacheRefreshBus.markSdkAuthoritative]) and this rebuild
        // consults that register. Pre-cutover nothing registers, so the flag is always
        // false and behaviour is byte-for-byte unchanged.
        sdkAuthoritative = displayCacheRefreshBus.isSdkAuthoritative(entry.rowId)
    )

    /** Batch [mergePreservingSdkStamped] over [entries] with one Room read for the existing rows. */
    private suspend fun mergeAllPreservingSdkStamped(
        entries: List<TxDisplayCacheEntry>
    ): List<TxDisplayCacheEntry> {
        if (entries.isEmpty()) return entries
        // Chunked: SQLite's IN-clause variable cap is 999 and rescan batches can be large.
        val existingByRowId = HashMap<String, TxDisplayCacheEntry>(entries.size)
        for (chunk in entries.map { it.rowId }.chunked(500)) {
            txDisplayCacheDao.getEntriesByIds(chunk).forEach { existingByRowId[it.rowId] = it }
        }
        return entries.map { mergePreservingSdkStamped(it, existingByRowId[it.rowId]) }
    }

    private fun computeFilterFlags(wrapper: TransactionWrapper): Int {
        val bag = walletData.transactionBag
        var flags = 0
        if (wrapper is CoinJoinMixingTxSet) {
            flags = TxDisplayCacheEntry.FLAG_COINJOIN
        } else {
            if (wrapper.transactions.values.any { TxDirectionFilter(TxFilterType.SENT, bag).matches(it.dashjTx) }) {
                flags = flags or TxDisplayCacheEntry.FLAG_SENT
            }
            if (wrapper.transactions.values.any { TxDirectionFilter(TxFilterType.RECEIVED, bag).matches(it.dashjTx) }) {
                flags = flags or TxDisplayCacheEntry.FLAG_RECEIVED
            }
            val firstTxId = wrapper.transactions.keys.first()
            if (ServiceName.isDashSpend(metadata[TxId.wrap(firstTxId)]?.service)) {
                flags = flags or TxDisplayCacheEntry.FLAG_GIFT_CARD or TxDisplayCacheEntry.FLAG_SENT
            }
        }
        return flags
    }

    private fun TxFilterType.toFilterFlag(): Int = when (this) {
        TxFilterType.SENT      -> TxDisplayCacheEntry.FLAG_SENT
        TxFilterType.RECEIVED  -> TxDisplayCacheEntry.FLAG_RECEIVED
        TxFilterType.GIFT_CARD -> TxDisplayCacheEntry.FLAG_GIFT_CARD
        TxFilterType.ALL       -> 0
    }

    @VisibleForTesting
    fun close() {
        serviceScope.cancel()
    }
}

/**
 * PURE merge of a dashj-rebuilt display [entry] over the [existing] cached row —
 * the host-testable core of [TxDisplayCacheService.mergePreservingSdkStamped]
 * (see that method's KDoc for the full rationale). Kept free of Android/Room so
 * the guard's carve-outs can be unit-tested directly.
 *
 * @param sdkAuthoritative whether the SDK sync pipeline has claimed authority over
 *        this rowId ([DisplayCacheRefreshBus.markSdkAuthoritative]) — the signal
 *        that identifies a NON-contact SDK-stamped row. Always false pre-cutover.
 */
internal fun mergeDisplayEntryPreservingSdkStamped(
    entry: TxDisplayCacheEntry,
    existing: TxDisplayCacheEntry?,
    sendingTitle: String,
    sentTitle: String,
    sdkAuthoritative: Boolean
): TxDisplayCacheEntry {
    existing ?: return entry
    var result = entry
    if (existing.service != null && result.service == null) {
        result = result.copy(
            service      = existing.service,
            iconType     = existing.iconType,
            iconBgType   = existing.iconBgType,
            customIconId = result.customIconId ?: existing.customIconId
        )
    }
    val degenerateRebuild = entry.valueSatoshis == 0L && existing.valueSatoshis != 0L
    if (degenerateRebuild) {
        result = result.copy(valueSatoshis = existing.valueSatoshis)
    }
    if (result.exchangeRateFiatCode == null && existing.exchangeRateFiatCode != null) {
        result = result.copy(
            exchangeRateFiatCode  = existing.exchangeRateFiatCode,
            exchangeRateFiatValue = existing.exchangeRateFiatValue
        )
    }
    if (result.contactUserId == null && existing.contactUserId != null) {
        result = result.copy(
            contactUsername    = existing.contactUsername,
            contactDisplayName = existing.contactDisplayName,
            contactAvatarUrl   = existing.contactAvatarUrl,
            contactUserId      = existing.contactUserId
        )
    }
    // FULL shape freeze for contact/SDK-stamped rows. The previous guard only
    // froze on a SENT↔RECEIVED direction flip or a value-0 rebuild — verified
    // on-device to be full of holes: a contact send of −0.4 was rewritten by
    // this dashj-side path to −260 (fee-only: dashj counts the friendship
    // payment output as watched-own, so its net degenerates to −fee) with the
    // direction UNCHANGED and the value non-zero, so no rule fired and the
    // wrong value persisted. The dashj wrapper can never recompute the
    // authoritative value/direction of an SDK-authored tx (only the SDK's signed
    // wallet net can), so when the existing row carries a contact identity, is
    // registered as SDK-AUTHORITATIVE, or the rebuild is degenerate, NOTHING
    // display-shaping from the rebuild is trusted: value, icon, title, status and
    // filter bucket all stay as cached. The ONLY refresh allowed through is
    // same-direction status PROGRESS — dashj's legitimate job: flipping a
    // "Sending" title to "Sent", and clearing a stale secondary status
    // ("Processing"/"Confirming" → none). dashj may never (re)introduce a
    // secondary status or relabel an SDK-authored title ("Shielded", "Invitation").
    // Pre-cutover a contact row is rebuilt from the SAME dashj computation
    // that produced it, so the freeze is value-identical there (the one
    // deliberate exception: a contact row's "Processing"→"Confirming" text
    // swap no longer flows through these writers — cosmetic only).
    // Rows without contact data, not SDK-registered and non-degenerate are
    // untouched: byte-for-byte pre-cutover behavior.
    // dashj stays AUTHORITATIVE for transaction ERRORS (dead / conflicting /
    // double-spent): the SDK display path has no error concept and the planner carves
    // error rows out of every re-stamp, so a rebuild that newly reports hasErrors must
    // pass through whole — otherwise a failed tx would keep its "Sent" icon and title.
    val newlyErrored = entry.hasErrors && !existing.hasErrors
    val sdkStamped = existing.contactUserId != null || sdkAuthoritative
    if (!newlyErrored && (sdkStamped || degenerateRebuild)) {
        val sameDirection = entry.iconType == existing.iconType
        val allowStatusProgress = sameDirection && !degenerateRebuild
        val sendingToSent = allowStatusProgress &&
            existing.title == sendingTitle && entry.title == sentTitle
        val statusCleared = allowStatusProgress &&
            entry.statusText.isEmpty() && existing.statusText.isNotEmpty()
        result = result.copy(
            valueSatoshis = existing.valueSatoshis,
            iconType      = existing.iconType,
            iconBgType    = existing.iconBgType,
            title         = if (sendingToSent) entry.title else existing.title,
            statusText    = if (statusCleared) entry.statusText else existing.statusText,
            filterFlags   = existing.filterFlags
        )
    }
    return result
}

/**
 * Whether rebuilding the display/group caches from the dashj wallet would
 * DESTROY the user's visible history rather than refresh it.
 *
 * Post-cutover the dashj wallet is held with zero transactions while the SDK
 * feeds the caches, so a rebuild from it wipes both tables and re-populates
 * them from nothing — and no dashj-sourced path ever fills them again.
 */
internal fun dashjRebuildWouldEraseHistory(cutoverCommitted: Boolean, dashjTxCount: Int): Boolean =
    cutoverCommitted && dashjTxCount == 0
