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
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.transactions.coinjoin.CoinJoinMixingTxSet
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxWrapperFactory
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.main.HistoryRowView
import de.schildbach.wallet.ui.transactions.TransactionRowView
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.batchAndFilterUpdates
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSetFactory
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TxDisplayCacheService @Inject constructor(
    private val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val txGroupCacheDao: TxGroupCacheDao,
    private val metadataProvider: TransactionMetadataProvider,
    private val platformRepo: PlatformRepo,
    private val blockchainStateProvider: BlockchainStateProvider
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

    private var metadata: Map<Sha256Hash, PresentableTxMetadata> = mapOf()
    private var contacts: Map<String, DashPayProfile> = mapOf()
    @Volatile private var contactsByTxId: Map<String, DashPayProfile> = mapOf()
    private var minContactCreatedDate: LocalDate = LocalDate.now()
    private var chainLockBlockHeight: Int = 0

    private lateinit var crowdNodeWrapperFactory: FullCrowdNodeSignUpTxSetFactory
    private lateinit var coinJoinWrapperFactory: CoinJoinTxWrapperFactory

    /**
     * Pre-built rows for the fast startup phase (from Room display cache).
     * Emits emptyList() once the live pager takes over.
     */
    val cachedRows: StateFlow<List<HistoryRowView>> = _txDataSource
        .map { source -> if (source is TxDataSource.PrebuiltCache) source.rows else emptyList() }
        .stateIn(serviceScope, SharingStarted.Eagerly, emptyList())

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
                                entry.toTransactionRowView(contactsByTxId[entry.rowId]) as HistoryRowView
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
            log.info("STARTUP tx_display_cache loaded {} rows in {}ms",
                cachedRows.size, System.currentTimeMillis() - t0)
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
                if (_txDataSource.value is TxDataSource.Empty) {
                    _txDataSource.value = TxDataSource.PrebuiltCache(historyRows)
                    _transactionsLoaded.value = true
                }
            }
        }

        _currentFilter
            .flatMapLatest { direction ->
                val filter = TxDirectionFilter(direction, walletData.wallet!!)
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
                    _currentPagingSource.value?.invalidate()
                }
                walletData.observeTransactions(true, filter)
                    .batchAndFilterUpdates(BATCHING_PERIOD)
                    .onEach { txs -> updateWrappedListForTransactions(txs, filter) }
            }
            .catch { e -> log.error("transactionsDirection flow error", e) }
            .launchIn(serviceScope)

        metadataProvider.observePresentableMetadata()
            .onEach { newMetadata ->
                val oldMetadata = this.metadata
                this.metadata = newMetadata

                val changedIds = buildSet<Sha256Hash> {
                    newMetadata.forEach { (id, meta) -> if (meta != oldMetadata[id]) add(id) }
                    oldMetadata.forEach { (id, _) -> if (id !in newMetadata) add(id) }
                }

                if (changedIds.isEmpty()) return@onEach

                val inMemoryWrappers = wrappedTransactionList.filter { wrapper ->
                    wrapper.transactions.keys.any { it in changedIds }
                }
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
                    val rowIds = newEntries.map { it.rowId }
                    val existingByRowId = txDisplayCacheDao.getEntriesByIds(rowIds).associateBy { it.rowId }
                    val entries = newEntries.map { entry ->
                        val existing = existingByRowId[entry.rowId]
                        if (existing != null && existing.service != null && entry.service == null) {
                            entry.copy(
                                service    = existing.service,
                                iconType   = existing.iconType,
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
            .launchIn(serviceScope)

        platformRepo.observeContacts(
            "",
            de.schildbach.wallet.data.UsernameSortOrderBy.LAST_ACTIVITY,
            false
        ).distinctUntilChanged()
            .onEach { contacts ->
                this.minContactCreatedDate = contacts.minOfOrNull { it.dashPayProfile.createdAt }?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: LocalDate.now()
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
                serviceScope.launch {
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
            .launchIn(serviceScope)

        blockchainStateProvider.observeState()
            .onEach { state ->
                if (state != null) {
                    chainLockBlockHeight = state.chainlockHeight
                }
            }
            .catch { e -> log.error("blockchain state flow error (cache service)", e) }
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
     * Wipes both caches and triggers a full rebuild from the current wallet state.
     */
    fun forceRebuildTransactionCache() {
        serviceScope.launch {
            txDisplayCacheDao.deleteAll()
            txGroupCacheDao.deleteAll()
            wrappedTransactionList = emptyList()
            _txDataSource.value = TxDataSource.Empty
            val filter = TxDirectionFilter(_currentFilter.value, walletData.wallet!!)
            rebuildWrappedList(filter)
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

    private suspend fun persistGroupCache(wrappers: Collection<TransactionWrapper>) {
        val entries = wrappers.flatMap { wrapper ->
            val type = when (wrapper) {
                is CoinJoinMixingTxSet     -> TxGroupCacheEntry.TYPE_COINJOIN
                is FullCrowdNodeSignUpTxSet -> TxGroupCacheEntry.TYPE_CROWDNODE
                else                       -> TxGroupCacheEntry.TYPE_SINGLE
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

        if (wrappedTransactionList.none { it.id == wrapper.id }) {
            wrappedTransactionList = (wrappedTransactionList + wrapper)
                .sortedByDescending { it.groupDate }
        }
        return wrapper
    }

    private suspend fun updateWrappedListForTransactions(txs: List<Transaction>, filter: TxDirectionFilter) {
        val txIdToWrapper = HashMap<String, TransactionWrapper>(wrappedTransactionList.size * 4)
        wrappedTransactionList.forEach { wrapper ->
            wrapper.transactions.keys.forEach { txId ->
                txIdToWrapper[txId.toString()] = wrapper
            }
        }

        val mutableList = wrappedTransactionList.toMutableList()
        val affectedWrappers = mutableSetOf<TransactionWrapper>()
        val unknownTxs = mutableListOf<Transaction>()

        for (tx in txs) {
            val existing = txIdToWrapper[tx.txId.toString()]
            if (existing != null) {
                existing.transactions[tx.txId] = tx
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
                        wrapper.transactions[tx.txId] = tx
                        affectedWrappers.add(wrapper)
                        if (wrapper.passesFilter(filter, metadata) && mutableList.none { it.id == wrapper.id }) {
                            mutableList.add(wrapper)
                        }
                        continue
                    }
                }

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

        val displayEntries = affectedWrappers.map { wrapper ->
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

        val groupEntries = affectedWrappers.flatMap { wrapper ->
            val type = when (wrapper) {
                is CoinJoinMixingTxSet     -> TxGroupCacheEntry.TYPE_COINJOIN
                is FullCrowdNodeSignUpTxSet -> TxGroupCacheEntry.TYPE_CROWDNODE
                else                       -> TxGroupCacheEntry.TYPE_SINGLE
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

        log.info("updateWrappedListForTransactions: updated {} wrappers", affectedWrappers.size)

        if (unknownTxs.isNotEmpty()) {
            resolveContactsForTransactions(unknownTxs, affectedWrappers)
        }
    }

    private suspend fun resolveAllContacts() {
        if (contacts.isEmpty()) return
        if (!platformRepo.hasBlockchainIdentity) return

        val txsToResolve = wrappedTransactionList
            .map { it.transactions.values.first() }
            .filter { tx ->
                !tx.isEntirelySelf(walletData.transactionBag) &&
                    tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() >= minContactCreatedDate &&
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
                            platformRepo.blockchainIdentity.getContactForTransaction(tx)?.let { id ->
                                contactsSnapshot[id]?.let { profile -> tx.txId.toString() to profile }
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

    private suspend fun resolveContactsForTransactions(
        newTxs: List<Transaction>,
        affectedWrappers: Set<TransactionWrapper>
    ) {
        if (contacts.isEmpty() || !platformRepo.hasBlockchainIdentity) return

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
                            platformRepo.blockchainIdentity.getContactForTransaction(tx)
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
                    contact = contactsByTxId[txId.toString()],
                    metadata = metadata[txId],
                    chainLockBlockHeight = chainLockBlockHeight
                )
                TxDisplayCacheEntry.fromTransactionRowView(
                    row,
                    walletApplication,
                    computeFilterFlags(wrapper)
                )
            }
        if (updatedEntries.isNotEmpty()) {
            txDisplayCacheDao.insertAll(updatedEntries)
        }
    }

    private fun createSingleTxWrapper(tx: Transaction): TransactionWrapper = object : TransactionWrapper {
        override val id           = tx.txId.toString()
        override val transactions = hashMapOf(tx.txId to tx)
        override val groupDate    = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        override fun tryInclude(t: Transaction) = true
        override fun getValue(bag: org.bitcoinj.core.TransactionBag) = tx.getValue(bag)
    }

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

    private fun TxFilterType.toFilterFlag(): Int = when (this) {
        TxFilterType.SENT      -> TxDisplayCacheEntry.FLAG_SENT
        TxFilterType.RECEIVED  -> TxDisplayCacheEntry.FLAG_RECEIVED
        TxFilterType.GIFT_CARD -> TxDisplayCacheEntry.FLAG_GIFT_CARD
        TxFilterType.ALL       -> 0
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
}