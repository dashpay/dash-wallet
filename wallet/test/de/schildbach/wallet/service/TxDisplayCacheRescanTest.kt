/*
 * Copyright 2026 Dash Core Group.
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

import androidx.paging.PagingSource
import androidx.paging.PagingState
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.database.entity.TxGroupCacheEntry
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.CutoverState
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxWrapperFactory
import de.schildbach.wallet.transactions.toTxInfo
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TxInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * MO-1054 end to end through [TxDisplayCacheService]: a user rescan clears the
 * caches twice — the service teardown ([TxDisplayCacheService.clearDatabaseForRescan])
 * and, on the next service start, the dashj `wallet.reset()` the missing-blockstore
 * branch performs on the held, empty dashj wallet (dashj fires the reset event
 * even then). Post-cutover both must keep the SDK-fed rows; pre-cutover both
 * still clear; a wallet wipe always clears.
 */
class TxDisplayCacheRescanTest {

    private val walletReset = MutableSharedFlow<Unit>(replay = 1)
    private val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
    private val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
    private val cutoverUi = mockk<CutoverUiDataService>(relaxed = true)
    private val dashPayConfig = mockk<DashPayConfig>(relaxed = true)
    private var service: TxDisplayCacheService? = null

    /**
     * A real [TxDisplayCacheService] at [cutover] with every feed but the
     * dashj reset event ([walletReset]) empty, so only the rescan paths act.
     */
    private fun service(cutover: CutoverState): TxDisplayCacheService {
        coEvery { dashPayConfig.get(DashPayConfig.CUTOVER_STATE) } returns cutover.name
        coEvery { displayDao.getAll() } returns emptyList()
        coEvery { displayDao.getCount() } returns 3_272
        val walletData = mockk<WalletData>(relaxed = true) {
            every { observeWallet() } returns flowOf(null)
            every { observeWalletReset() } returns walletReset
            every { wallet } returns null
        }
        val metadata = mockk<TransactionMetadataProvider>(relaxed = true) {
            every { observePresentableMetadata() } returns emptyFlow()
        }
        val identityRepo = mockk<IdentityRepository>(relaxed = true) {
            every { observeContacts(any(), any(), any()) } returns emptyFlow()
        }
        val blockchainState = mockk<BlockchainStateProvider>(relaxed = true) {
            every { observeState() } returns emptyFlow()
        }
        return TxDisplayCacheService(
            walletData = walletData,
            walletApplication = mockk<WalletApplication>(relaxed = true),
            txDisplayCacheDao = displayDao,
            txGroupCacheDao = groupDao,
            metadataProvider = metadata,
            platformRepo = mockk<PlatformRepo>(relaxed = true),
            identityRepo = identityRepo,
            blockchainStateProvider = blockchainState,
            displayCacheRefreshBus = DisplayCacheRefreshBus(),
            dashPayConfig = dashPayConfig,
            cutoverUiDataService = { cutoverUi },
            l1SyncStatusService = { mockk<L1SyncStatusService>(relaxed = true) }
        ).also { service = it }
    }

    private val createdPagingSources = CopyOnWriteArrayList<FakePagingSource>()
    private val wrapAllCoinJoinFactories = CopyOnWriteArrayList<CoinJoinTxWrapperFactory>()
    private var collectJob: Job? = null

    /**
     * A real [TxDisplayCacheService] with a nonempty display cache (no CoinJoin/CrowdNode
     * groups) and a live [walletFlow], for the direction-change/wallet-replacement
     * discriminator in the combine/flatMapLatest chain — [service] above always emits a
     * null wallet, so it never reaches those branches.
     */
    private fun serviceWithWallet(
        walletFlow: StateFlow<Wallet?>,
        rebuildWraps: Collection<TransactionWrapper> = emptyList(),
        firstCountReadThrows: Boolean = false,
        // Stubbed BEFORE the service exists for the same construction-race reason
        // as firstCountReadThrows above.
        groupReads: (suspend () -> List<TxGroupCacheEntry>)? = null,
        // Same pre-construction stubbing rule; getCount() is the only suspension
        // point the combine pipeline hits while _txDataSource is still Empty
        // (RoomLive is set before initializeFactoriesFromCache), so holding it
        // is how a test keeps the source Empty.
        countReads: (suspend () -> Int)? = null,
        // The dashj stream the incremental collector subscribes; null keeps the
        // relaxed-mock default (an immediately completing flow).
        txStream: Flow<Transaction>? = null
    ): TxDisplayCacheService {
        coEvery { displayDao.getAll() } returns emptyList()
        if (firstCountReadThrows) {
            // Stubbed BEFORE the service exists: its combine pipeline starts at
            // construction, so restubbing afterwards would race the in-flight first
            // getCount() call (see the getActiveGroups comment further down).
            val firstReads = AtomicInteger(0)
            coEvery { displayDao.getCount() } coAnswers {
                if (firstReads.incrementAndGet() == 1) throw RuntimeException("injected Room failure") else 3_272
            }
        } else if (countReads != null) {
            coEvery { displayDao.getCount() } coAnswers { countReads() }
        } else {
            coEvery { displayDao.getCount() } returns 3_272
        }
        if (groupReads == null) {
            coEvery { groupDao.getActiveGroups(any()) } returns emptyList()
        } else {
            coEvery { groupDao.getActiveGroups(any()) } coAnswers { groupReads() }
        }
        every { displayDao.pagingSource(any()) } answers { FakePagingSource(firstArg()).also { createdPagingSources += it } }
        val walletData = mockk<WalletData>(relaxed = true) {
            every { observeWallet() } returns walletFlow
            every { observeWalletReset() } returns emptyFlow()
            every { wallet } answers { walletFlow.value }
            if (txStream != null) {
                every { observeTransactions(any(), *anyVararg()) } returns txStream
            }
            every { wrapAllTransactions(any(), any()) } answers {
                // Record the CoinJoin factory each rebuild wraps with (the vararg may
                // surface expanded or as one array, so scan rather than index). The
                // CopyOnWriteArrayList is appended on the service's worker thread and
                // read on the test thread.
                wrapAllCoinJoinFactories += invocation.args
                    .flatMap { arg -> if (arg is Array<*>) arg.toList() else listOf(arg) }
                    .filterIsInstance<CoinJoinTxWrapperFactory>()
                rebuildWraps
            }
        }
        val metadata = mockk<TransactionMetadataProvider>(relaxed = true) {
            every { observePresentableMetadata() } returns emptyFlow()
        }
        val identityRepo = mockk<IdentityRepository>(relaxed = true) {
            every { observeContacts(any(), any(), any()) } returns emptyFlow()
        }
        val blockchainState = mockk<BlockchainStateProvider>(relaxed = true) {
            every { observeState() } returns emptyFlow()
        }
        return TxDisplayCacheService(
            walletData = walletData,
            walletApplication = mockk<WalletApplication>(relaxed = true),
            txDisplayCacheDao = displayDao,
            txGroupCacheDao = groupDao,
            metadataProvider = metadata,
            platformRepo = mockk<PlatformRepo>(relaxed = true),
            identityRepo = identityRepo,
            blockchainStateProvider = blockchainState,
            displayCacheRefreshBus = DisplayCacheRefreshBus(),
            dashPayConfig = dashPayConfig,
            cutoverUiDataService = { cutoverUi },
            l1SyncStatusService = { mockk<L1SyncStatusService>(relaxed = true) }
        ).also { service = it }
    }

    private suspend fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    /** A minimal single-tx wrapper over a real dashj [tx], shaped like the service's own. */
    private fun singleTxWrapper(tx: Transaction, params: NetworkParameters): TransactionWrapper {
        Context.getOrCreate(params) // toTxInfo reads tx confidence through dashj's ambient Context
        val txInfo = tx.toTxInfo(mockk<TransactionBag>(relaxed = true), params)
        return object : TransactionWrapper {
            override val id = tx.txId.toString()
            override val transactions = hashMapOf(txInfo.txId to txInfo)
            override val groupDate = txInfo.groupDate
            override fun tryInclude(tx: TxInfo) = tx.txId == txInfo.txId
            override fun getValue() = Dash(txInfo.netValueDuffs)
        }
    }

    private fun TxDisplayCacheService.coinJoinWrapperFactory(): CoinJoinTxWrapperFactory? {
        val field = TxDisplayCacheService::class.java.getDeclaredField("coinJoinWrapperFactory")
        field.isAccessible = true
        return field.get(this) as CoinJoinTxWrapperFactory?
    }

    /**
     * A real (non-mock) PagingSource so Paging3's own invalidate()/refresh machinery runs.
     * Remembers the [filterFlag] it was queried with, so tests can assert a recreated
     * source actually carries the newly picked direction instead of only counting sources.
     */
    private class FakePagingSource(val filterFlag: Int) : PagingSource<Int, TxDisplayCacheEntry>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TxDisplayCacheEntry> =
            LoadResult.Page(emptyList(), null, null)
        override fun getRefreshKey(state: PagingState<Int, TxDisplayCacheEntry>): Int? = null
    }

    @After
    fun tearDown() {
        collectJob?.cancel()
        service?.serviceScope?.cancel()
    }

    @Test
    fun postCutoverRescanKeepsTheRowsThroughTeardownAndTheRestartReset() = runBlocking {
        val cache = service(CutoverState.CUT_OVER)

        cache.clearDatabaseForRescan()   // service teardown
        walletReset.emit(Unit)           // next start: missing blockstore → wallet.reset()

        // Both clears asked for a reconcile walk in place of the wipe; the
        // second one runs on the service's own worker, so wait for it.
        verify(timeout = 5_000, exactly = 2) { cutoverUi.requestFullReconcile() }
        coVerify(exactly = 0) { displayDao.deleteAll() }
        coVerify(exactly = 0) { displayDao.deleteAllL1() }
        coVerify(exactly = 0) { groupDao.deleteAll() }
    }

    @Test
    fun preCutoverRescanStillClearsOnBothPaths() = runBlocking {
        val cache = service(CutoverState.DUAL_RUNNING)

        cache.clearDatabaseForRescan()
        coVerify(exactly = 1) { displayDao.deleteAll() }

        walletReset.emit(Unit)
        // The dashj wallet-reset clear drops the L1 rows only: shielded-activity
        // rows are not dashj-derived and nothing dashj-side would re-write them.
        coVerify(timeout = 5_000, exactly = 1) { displayDao.deleteAllL1() }
        coVerify(exactly = 1) { displayDao.deleteAll() }
        coVerify(exactly = 2) { groupDao.deleteAll() }
        verify(exactly = 0) { cutoverUi.requestFullReconcile() }
    }

    @Test
    fun postCutoverWalletWipeStillClears() = runBlocking {
        val cache = service(CutoverState.CUT_OVER)

        cache.clearDatabase()

        coVerify(exactly = 1) { displayDao.deleteAll() }
        coVerify(exactly = 1) { groupDao.deleteAll() }
    }

    /**
     * MO-1109 regression (a): a nonempty display cache with no active CoinJoin/CrowdNode
     * groups, then repeated direction changes. Each change must invalidate the live Pager
     * (so the new filter actually re-queries) without re-running initializeFactoriesFromCache
     * — that only happens once, on the initial RoomLive transition.
     */
    @Test
    fun directionChangesInvalidateTheActivePagerWithoutReinitializingFactories() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val cache = serviceWithWallet(MutableStateFlow(wallet))

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { createdPagingSources.isNotEmpty() }
        assertEquals(0, createdPagingSources[0].filterFlag) // initial query is unfiltered (ALL)

        cache.setFilter(TxFilterType.SENT)
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, createdPagingSources[1].filterFlag)

        cache.setFilter(TxFilterType.RECEIVED)
        awaitTrue { createdPagingSources[1].invalid }
        awaitTrue { createdPagingSources.size >= 3 }
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, createdPagingSources[2].filterFlag)

        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
    }

    /**
     * MO-1109 regression (e): a wallet reset (observeWalletReset) rebinds coinJoinWrapperFactory
     * to the same wallet and leaves _txDataSource at RoomLive, but it also clears the display
     * cache. A direction change afterwards, with the cache still empty, must rebuild the wrapped
     * list from the wallet rather than only flip the filter flag on an empty cache — the fast
     * path in the service's combine collector requires both the same-wallet identity AND a
     * nonzero row count.
     * serviceWithWallet() has no reset flow to drive, so the emptied cache is simulated by
     * restubbing getCount() to 0 for the emission the direction change triggers.
     */
    @Test
    fun directionChangeWithEmptyCacheAfterResetRebuildsInsteadOfFlippingFlag() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        // One real transaction for the rebuild to find, so the test proves what the
        // rebuild WRITES (the wallet's tx rendered into a display row), not merely
        // that replaceAll ran.
        val params = TestNet3Params.get()
        val tx = Transaction(params).apply {
            addOutput(Coin.parseCoin("0.5"), Address.fromBase58(params, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"))
        }
        // CopyOnWriteArrayList: the capture appends on the service's worker thread and the
        // assertions read on the test thread.
        val replacedRows = CopyOnWriteArrayList<List<TxDisplayCacheEntry>>()
        coEvery { displayDao.replaceAll(capture(replacedRows)) } just Runs
        val cache = serviceWithWallet(MutableStateFlow(wallet), rebuildWraps = listOf(singleTxWrapper(tx, params)))

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { createdPagingSources.isNotEmpty() }
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
        assertSame(wallet, cache.coinJoinWrapperFactory()?.wallet)

        coEvery { displayDao.getCount() } returns 0
        cache.setFilter(TxFilterType.SENT)

        coVerify(timeout = 5_000) { displayDao.replaceAll(any()) }
        // coVerify returns once the call is RECORDED, which does not order with the
        // capture-append on the worker thread — await the captured rows before reading them.
        awaitTrue { replacedRows.isNotEmpty() }
        assertEquals(1, replacedRows.last().size)
        assertEquals(tx.txId.toString(), replacedRows.last().first().rowId)
        // The rebuild is bound to the current wallet: both the factory it wrapped
        // with and the one it republished hold it (the wrapAllTransactions stub itself
        // ignores its wallet argument, so this is what pins it).
        assertSame(wallet, wrapAllCoinJoinFactories.last().wallet)
        assertSame(wallet, cache.coinJoinWrapperFactory()?.wallet)
        // Still 1: the reset re-hydrates by walking the wallet (rebuildWrappedList), not by
        // re-reading the (now-cleared) CoinJoin/CrowdNode group cache.
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
    }

    /**
     * MO-1109 regression (f): the row-count read runs on every filter emission, so a one-off
     * Room failure there must not complete the combine subscription through its catch{} — the
     * same-wallet pick treats the unreadable count as UNKNOWN and still takes the flag-only
     * fast path (no rebuild on a cache that may well have rows), and the NEXT pick working
     * proves the collector survived the throw.
     */
    @Test
    fun countReadFailureOnDirectionChangeStillInvalidatesAndKeepsTheCollectorAlive() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val cache = serviceWithWallet(MutableStateFlow(wallet))

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { createdPagingSources.isNotEmpty() }
        assertSame(wallet, cache.coinJoinWrapperFactory()?.wallet)

        // Restubbed only after the initial emission has provably finished (same quiescence
        // point the empty-cache test uses), so it cannot race an in-flight getCount() call.
        val countReads = AtomicInteger(0)
        coEvery { displayDao.getCount() } coAnswers {
            if (countReads.incrementAndGet() == 1) throw RuntimeException("injected Room failure") else 3_272
        }

        cache.setFilter(TxFilterType.SENT) // the count read throws → UNKNOWN → fast path
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, createdPagingSources[1].filterFlag)

        cache.setFilter(TxFilterType.RECEIVED) // only a live collector can see this pick
        awaitTrue { createdPagingSources[1].invalid }
        awaitTrue { createdPagingSources.size >= 3 }
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, createdPagingSources[2].filterFlag)

        // Neither pick re-hydrated or rebuilt: the unreadable count stayed on the fast path.
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
        coVerify(exactly = 0) { displayDao.replaceAll(any()) }
    }

    /**
     * MO-1109 regression (g): the count read fails on the FIRST emission, before any
     * pipeline is RoomLive — e.g. a transient Room failure at startup. The held dashj
     * wallet can be empty post-cutover while the SDK-owned cache holds the real history,
     * so an unknown count must take the non-destructive warm-cache path (read-only
     * initializeFactoriesFromCache, then RoomLive), NEVER the rebuildWrappedList fallback
     * whose replaceAll would wipe the cache from dashj — the exact destructive rebuild
     * forceRebuildTransactionCache refuses. Only a KNOWN zero count may rebuild.
     */
    @Test
    fun countReadFailureOnFirstEmissionTakesTheWarmPathAndNeverRebuilds() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val cache = serviceWithWallet(MutableStateFlow(wallet), firstCountReadThrows = true)

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }

        // The warm-cache branch finished: factories hydrated from the cache tables,
        // the factory published for THIS wallet, and RoomLive set (only a RoomLive
        // pipeline builds a Room PagingSource).
        awaitTrue { createdPagingSources.isNotEmpty() }
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === wallet }
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
        // Asserted only after awaiting the warm-branch effects above, so a rebuild
        // in flight could not slip in after the check.
        coVerify(exactly = 0) { displayDao.replaceAll(any()) }

        // A later direction change still works: the second count read succeeds and the
        // now-RoomLive fast path invalidates the Pager.
        cache.setFilter(TxFilterType.SENT)
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, createdPagingSources[1].filterFlag)
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
        coVerify(exactly = 0) { displayDao.replaceAll(any()) }
    }

    /**
     * MO-1109 regression (i): the warm-cache branch must publish the live pipeline
     * (RoomLive, Pager invalidation, transactionsLoaded) BEFORE hydrating the group
     * factories, and a getActiveGroups failure must neither take the live list down nor
     * end the filter subscription — the hydration only enriches CoinJoin/CrowdNode rows
     * over a perfectly good cache. The retry lives in the wallet-identity term of the
     * same-wallet fast-path check: the factory is published only after hydration
     * finishes, so a direction change cancelling an in-flight hydration — or following
     * a THROWN read, which likewise leaves the factory unpublished — re-enters the warm
     * path (getActiveGroups runs again) instead of taking the flag-only fast path. The
     * warm re-entry is staged here via a cancelled first hydration, the throw lands on
     * the retry's read, and a third pick proves the subscription survived the throw.
     */
    @Test
    fun groupReadFailureOnTheWarmPathStillStartsTheLiveList() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val groupReadCalls = AtomicInteger(0)
        val firstHydrationCancelled = CompletableDeferred<Unit>()
        val cache = serviceWithWallet(
            MutableStateFlow(wallet),
            groupReads = {
                when (groupReadCalls.incrementAndGet()) {
                    1 -> try {
                        awaitCancellation()
                    } finally {
                        firstHydrationCancelled.complete(Unit)
                    }
                    2 -> throw RuntimeException("injected Room failure")
                    else -> emptyList()
                }
            }
        )

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }

        // The live pipeline is already up while the first hydration is still suspended
        // on its getActiveGroups call: loaded flipped, a Room paging source built, and
        // the factory NOT yet published for this wallet.
        awaitTrue { groupReadCalls.get() == 1 }
        awaitTrue { cache.transactionsLoaded.value }
        awaitTrue { createdPagingSources.isNotEmpty() }
        assertNull(cache.coinJoinWrapperFactory())

        // The pick cancels the suspended hydration; the unpublished factory fails the
        // same-wallet fast-path check, so it re-enters the warm path (a second
        // getActiveGroups read) instead of flipping the flag only — and that read throws.
        cache.setFilter(TxFilterType.SENT)
        withTimeout(5_000) { firstHydrationCancelled.await() }
        coVerify(timeout = 5_000, exactly = 2) { groupDao.getActiveGroups(any()) }

        // The live list survived the failed read: still loaded, the Pager re-queried
        // with the picked flag, and nothing rebuilt.
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
        assertEquals(TxDisplayCacheEntry.FLAG_SENT, createdPagingSources[1].filterFlag)
        assertTrue(cache.transactionsLoaded.value)
        coVerify(exactly = 0) { displayDao.replaceAll(any()) }

        // So did the filter subscription: a later pick (RECEIVED — _currentFilter is a
        // StateFlow, so re-picking SENT would not re-emit) still reaches the Pager, and
        // the factory the throw left unpublished routes it through the warm path again
        // (a third getActiveGroups read, which succeeds and publishes the factory).
        cache.setFilter(TxFilterType.RECEIVED)
        awaitTrue { createdPagingSources[1].invalid }
        awaitTrue { createdPagingSources.size >= 3 }
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, createdPagingSources[2].filterFlag)
        coVerify(timeout = 5_000, exactly = 3) { groupDao.getActiveGroups(any()) }
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === wallet }
        coVerify(exactly = 0) { displayDao.replaceAll(any()) }
    }

    /**
     * MO-1109 regression (j): a CAUGHT warm-path hydration failure must skip that
     * emission's incremental dashj collector (observeTransactions), not only keep the
     * subscription alive — the hydrated factories are the only thing that keeps
     * CoinJoin/CrowdNode rows grouped, and a row the collector persists ungrouped is
     * never regrouped by the retry. So the failed emission must not subscribe the
     * transaction stream at all, and the next pick, whose hydration succeeds, must
     * subscribe it and run its rows through the hydrated factories into the cache.
     */
    @Test
    fun hydrationFailureSkipsTheIncrementalCollectorUntilARetryHydrates() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val params = TestNet3Params.get()
        // The collector's toTxInfo runs on the service's worker thread, which fixes
        // itself up from the last-constructed dashj Context — create it here.
        Context.getOrCreate(params)
        val streamStarts = AtomicInteger(0)
        val txStream = MutableSharedFlow<Transaction>()
        // CopyOnWriteArrayList: appended on the service's worker thread, read here.
        val insertedRows = CopyOnWriteArrayList<List<TxDisplayCacheEntry>>()
        // Stubbed BEFORE the service exists for the same construction-race reason as
        // the group/count reads above.
        coEvery { displayDao.insertAll(capture(insertedRows)) } just Runs
        val groupReadCalls = AtomicInteger(0)
        val cache = serviceWithWallet(
            MutableStateFlow(wallet),
            groupReads = {
                if (groupReadCalls.incrementAndGet() == 1) {
                    throw RuntimeException("injected Room failure")
                } else {
                    emptyList()
                }
            },
            txStream = txStream.onStart { streamStarts.incrementAndGet() }
        )

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }

        // The first emission's hydration throws (and is caught): the live list is up,
        // but the factories stay unpublished and the dashj stream unsubscribed.
        coVerify(timeout = 5_000, exactly = 1) { groupDao.getActiveGroups(any()) }
        awaitTrue { cache.transactionsLoaded.value }
        awaitTrue { createdPagingSources.isNotEmpty() }
        assertNull(cache.coinJoinWrapperFactory())
        assertEquals(0, streamStarts.get())
        // A tx arriving in this window must have no effect: with no hydrated factories
        // it could only be persisted as an ungrouped standalone row. With no subscriber
        // the shared flow drops it; on a subscribed stream it would reach insertAll.
        txStream.emit(
            Transaction(params).apply {
                addOutput(Coin.parseCoin("0.33"), Address.fromBase58(params, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"))
            }
        )

        // The next pick re-enters the warm path (the factory the failure left
        // unpublished fails the same-wallet check); its hydration succeeds, publishes
        // the factories, and only then is the stream subscribed — exactly once.
        cache.setFilter(TxFilterType.SENT)
        coVerify(timeout = 5_000, exactly = 2) { groupDao.getActiveGroups(any()) }
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === wallet }
        awaitTrue { streamStarts.get() >= 1 }
        assertEquals(1, streamStarts.get())

        // A tx on the now-subscribed stream goes through the hydrated factories and
        // reaches the display cache...
        val tx = Transaction(params).apply {
            addOutput(Coin.parseCoin("0.5"), Address.fromBase58(params, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"))
        }
        txStream.emit(tx)
        awaitTrue { insertedRows.isNotEmpty() }
        assertEquals(tx.txId.toString(), insertedRows.last().first().rowId)
        // ...and nothing was ever persisted from the failed emission's window.
        assertTrue(insertedRows.flatten().all { it.rowId == tx.txId.toString() })
        coVerify(exactly = 0) { displayDao.replaceAll(any()) }
    }

    /**
     * MO-1109 regression (c): a wallet replacement whose hydration
     * (initializeFactoriesFromCache's getActiveGroups) is still suspended when a direction
     * change cancels it via flatMapLatest. coinJoinWrapperFactory must stay bound to the old
     * wallet until hydration actually finishes, so the next emission's identity check
     * correctly treats the new wallet as not-yet-hydrated and retries, instead of taking the
     * fast path on a factory that was published before its hydration completed.
     */
    @Test
    fun walletChangeCancelledMidHydrationRetriesInsteadOfSkippingIt() = runBlocking {
        val walletA = mockk<WalletEx>(relaxed = true)
        val walletB = mockk<WalletEx>(relaxed = true)
        val walletFlow = MutableStateFlow<Wallet?>(walletA)
        val hydrationBlocked = CompletableDeferred<Unit>()
        var firstBHydrationStarted = false
        val bHydrationAttempts = AtomicInteger(0)
        val cache = serviceWithWallet(walletFlow)

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { cache.coinJoinWrapperFactory() != null }
        assertSame(walletA, cache.coinJoinWrapperFactory()?.wallet)

        // Registered only AFTER wallet A's hydration has provably finished (the factory is
        // published): the service's combine pipeline starts at construction, so re-stubbing
        // this mock earlier raced wallet A's in-flight getActiveGroups call — MockK is not
        // safe against stubbing concurrent with an invocation on the same mock, and the
        // loser's exception killed the pipeline through its catch{}, leaving the factory
        // unpublished (flaked on the build host). The stub itself is keyed on wallet
        // identity, so it is a no-op for wallet A either way — only wallet B's *first*
        // hydration attempt ever suspends.
        coEvery { groupDao.getActiveGroups(any()) } coAnswers {
            if (walletFlow.value === walletB) {
                bHydrationAttempts.incrementAndGet()
                if (!firstBHydrationStarted) {
                    firstBHydrationStarted = true
                    hydrationBlocked.await()
                }
            }
            emptyList()
        }

        walletFlow.value = walletB
        awaitTrue { bHydrationAttempts.get() == 1 } // hydration for B is now suspended on hydrationBlocked

        cache.setFilter(TxFilterType.SENT) // cancels the suspended hydration via flatMapLatest
        awaitTrue { bHydrationAttempts.get() == 2 } // must retry, not skip, hydration

        hydrationBlocked.complete(Unit)
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === walletB }
        assertSame(walletB, cache.coinJoinWrapperFactory()?.wallet)
    }

    /**
     * MO-1109 regression (i): a cold start over a warm cache whose hydration
     * (initializeFactoriesFromCache's getActiveGroups) is still suspended when an on-demand
     * group load (a row tap, or the metadata observer) runs. loadWrapperOnDemand builds lazy
     * factories for its own call, and they must stay local: a published lazy factory is
     * bound to the current wallet without having hydrated it, so once a direction change
     * cancels the suspended hydration, the next emission would take the same-wallet fast
     * path and never hydrate — today's active CoinJoin/CrowdNode groups would stay unloaded
     * for the whole session.
     */
    @Test
    fun onDemandLoadDuringCancelledColdStartHydrationNeverSatisfiesTheFastPath() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val hydrationGate = CompletableDeferred<Unit>()
        val hydrationAttempts = AtomicInteger(0)
        // Registered BEFORE the service exists, like the other pre-construction stubs. The
        // entry's txId is not valid hex, so the on-demand load exercises the lazy-factory
        // branch and returns null without needing a real dashj transaction.
        coEvery { groupDao.getGroupEntries("coinjoin_2026-11-02") } returns listOf(
            TxGroupCacheEntry("coinjoin_2026-11-02", "not-a-txid", TxGroupCacheEntry.TYPE_COINJOIN, "2026-11-02", 0)
        )
        val cache = serviceWithWallet(
            MutableStateFlow(wallet),
            groupReads = {
                // The cold-start hydration suspends until it is cancelled; the retry returns.
                if (hydrationAttempts.incrementAndGet() == 1) hydrationGate.await()
                emptyList()
            }
        )

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { hydrationAttempts.get() == 1 } // the cold-start hydration is now suspended

        // An on-demand group load inside the hydration window builds lazy factories…
        cache.loadGroupWrapper("coinjoin_2026-11-02")
        // …but must not publish them: a published factory is the fast-path check's proof
        // that this wallet's hydration finished.
        assertNull(cache.coinJoinWrapperFactory())

        cache.setFilter(TxFilterType.SENT) // cancels the suspended hydration via flatMapLatest
        awaitTrue { hydrationAttempts.get() == 2 } // warm re-entry (retried hydration), not the fast path

        // The retry's read returned, so the factory that gets published is the hydrated one.
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === wallet }
    }

    /**
     * MO-1109 regression (h): a same-wallet rebuild (KNOWN zero count, as after a wallet
     * reset) whose replaceAll has already committed when a second direction change cancels
     * it via flatMapLatest. The factories that wrapped the rebuilt history must already be
     * published at that point — wrapAllTransactions does not suspend, so rebuildWrappedList
     * publishes them before its first suspension — because the next emission sees the
     * committed rows and takes the same-wallet fast path, which never re-hydrates. With a
     * post-commit publish the service kept whatever factories were installed before the
     * rebuild (e.g. the empty same-wallet ones a wallet reset installs) over the rebuilt rows.
     */
    @Test
    fun rebuildCancelledAfterReplaceAllCommitsStillPublishesItsFactories() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val cache = serviceWithWallet(MutableStateFlow(wallet))

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { createdPagingSources.isNotEmpty() }
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === wallet }
        val factoryBeforeRebuild = cache.coinJoinWrapperFactory()

        // Both restubbed only after the initial emission has provably finished (factory
        // published, source created), so neither races an in-flight call on its mock.
        val countReads = AtomicInteger(0)
        coEvery { displayDao.getCount() } coAnswers {
            // One KNOWN zero (the reset-emptied cache) routes the next pick into
            // rebuildWrappedList; after its replaceAll commits, the rows are back.
            if (countReads.incrementAndGet() == 1) 0 else 3_272
        }
        val replaceAllEntered = CompletableDeferred<Unit>()
        val replaceAllCancelled = CompletableDeferred<Unit>()
        val letReplaceAllReturn = CompletableDeferred<Unit>()
        coEvery { displayDao.replaceAll(any()) } coAnswers {
            replaceAllEntered.complete(Unit)
            // A suspend @Transaction DAO method is rolled back when a cancellation
            // lands inside its body (the count then stays a KNOWN zero, so the next
            // emission simply rebuilds again). This test models the other interleaving:
            // the transaction has already committed when the flatMapLatest cancellation
            // surfaces, as replaceAll resumes its (by then cancelled) caller. Suspend
            // until that cancellation arrives, then hold the "commit" open until the
            // test releases it; the CancellationException propagates as the stub
            // returns, like Room's own prompt cancellation after a commit.
            try {
                awaitCancellation()
            } finally {
                replaceAllCancelled.complete(Unit)
                withContext(NonCancellable) { letReplaceAllReturn.await() }
            }
        }

        cache.setFilter(TxFilterType.SENT) // KNOWN zero → rebuildWrappedList
        withTimeout(5_000) { replaceAllEntered.await() }
        cache.setFilter(TxFilterType.RECEIVED) // cancels the in-flight rebuild
        withTimeout(5_000) { replaceAllCancelled.await() } // cancel delivered mid-commit
        letReplaceAllReturn.complete(Unit)

        // The cancelled rebuild still published the exact factory that wrapped its rows.
        awaitTrue { cache.coinJoinWrapperFactory() !== factoryBeforeRebuild }
        assertEquals(1, wrapAllCoinJoinFactories.size)
        assertSame(wrapAllCoinJoinFactories[0], cache.coinJoinWrapperFactory())

        // The queued RECEIVED pick sees the committed rows and takes the same-wallet fast
        // path over them: Pager re-queried with the new flag, no re-hydration, no rebuild.
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
        assertEquals(TxDisplayCacheEntry.FLAG_RECEIVED, createdPagingSources[1].filterFlag)
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
        coVerify(exactly = 1) { displayDao.replaceAll(any()) }
        assertEquals(1, wrapAllCoinJoinFactories.size)
    }

    /**
     * MO-1109 regression (b): a warm display cache, then the observed wallet is replaced
     * (e.g. a restore over an existing one) while still warm. Initialization must re-run for
     * the new wallet, and the Pager built for the old one must be invalidated — reassigning
     * the RoomLive singleton emits nothing on its own, which was the bug this fixes.
     */
    @Test
    fun warmCacheWalletReplacementReinitializesAndInvalidatesThePager() = runBlocking {
        val walletA = mockk<WalletEx>(relaxed = true)
        val walletB = mockk<WalletEx>(relaxed = true)
        val walletFlow = MutableStateFlow<Wallet?>(walletA)
        val cache = serviceWithWallet(walletFlow)

        collectJob = CoroutineScope(Dispatchers.IO).launch { cache.transactions.collect {} }
        awaitTrue { createdPagingSources.isNotEmpty() }
        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
        assertSame(walletA, cache.coinJoinWrapperFactory()?.wallet)

        walletFlow.value = walletB

        coVerify(timeout = 5_000, exactly = 2) { groupDao.getActiveGroups(any()) }
        awaitTrue { cache.coinJoinWrapperFactory()?.wallet === walletB }
        assertSame(walletB, cache.coinJoinWrapperFactory()?.wallet)
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
    }

    /**
     * While the data source is still Empty the service must emit NOTHING. A synthetic
     * PagingData.empty() placeholder presents an empty list whose NotLoading refresh the
     * Fragment's cache→live swap gate cannot tell from a settled Room answer: with the
     * placeholder's load states dispatched after the worker had already flipped RoomLive
     * and transactionsLoaded, the gate detached the still-populated cache adapter over an
     * empty live list — and a failing first Room refresh left nothing to re-attach. The
     * first emission must therefore be the first Room generation. The source is held at
     * Empty by suspending the combine pipeline's getCount() read, its only suspension
     * point before RoomLive is set (factory hydration runs after that flip).
     */
    @Test
    fun nothingIsEmittedWhileTheSourceIsEmptyAndTheFirstEmissionIsARoomGeneration() = runBlocking {
        val wallet = mockk<WalletEx>(relaxed = true)
        val countReadEntered = CompletableDeferred<Unit>()
        val countReadGate = CompletableDeferred<Unit>()
        val cache = serviceWithWallet(
            MutableStateFlow(wallet),
            countReads = {
                countReadEntered.complete(Unit)
                countReadGate.await()
                3_272
            }
        )

        // The pipeline is provably suspended before it could leave Empty…
        withTimeout(5_000) { countReadEntered.await() }
        // …and while Empty, nothing is emitted and no paging source is built.
        assertNull(withTimeoutOrNull(1_000) { cache.transactions.first() })
        assertTrue(createdPagingSources.isEmpty())

        // Released, the warm path runs: the first emission is the first Room
        // generation, from exactly one paging source carrying the initial (ALL) flag.
        countReadGate.complete(Unit)
        assertNotNull(withTimeoutOrNull(5_000) { cache.transactions.first() })
        awaitTrue { createdPagingSources.size == 1 }
        assertEquals(0, createdPagingSources[0].filterFlag)
    }
}
