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
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.CutoverState
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxWrapperFactory
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

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
    private var collectJob: Job? = null

    /**
     * A real [TxDisplayCacheService] with a nonempty display cache (no CoinJoin/CrowdNode
     * groups) and a live [walletFlow], for the direction-change/wallet-replacement
     * discriminator in the combine/flatMapLatest chain — [service] above always emits a
     * null wallet, so it never reaches those branches.
     */
    private fun serviceWithWallet(walletFlow: StateFlow<Wallet?>): TxDisplayCacheService {
        coEvery { displayDao.getAll() } returns emptyList()
        coEvery { displayDao.getCount() } returns 3_272
        coEvery { groupDao.getActiveGroups(any()) } returns emptyList()
        every { displayDao.pagingSource(any()) } answers { FakePagingSource().also { createdPagingSources += it } }
        val walletData = mockk<WalletData>(relaxed = true) {
            every { observeWallet() } returns walletFlow
            every { observeWalletReset() } returns emptyFlow()
            every { wallet } answers { walletFlow.value }
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

    private fun TxDisplayCacheService.coinJoinWrapperFactory(): CoinJoinTxWrapperFactory? {
        val field = TxDisplayCacheService::class.java.getDeclaredField("coinJoinWrapperFactory")
        field.isAccessible = true
        return field.get(this) as CoinJoinTxWrapperFactory?
    }

    /** A real (non-mock) PagingSource so Paging3's own invalidate()/refresh machinery runs. */
    private class FakePagingSource : PagingSource<Int, TxDisplayCacheEntry>() {
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
        coVerify(exactly = 0) { groupDao.deleteAll() }
    }

    @Test
    fun preCutoverRescanStillClearsOnBothPaths() = runBlocking {
        val cache = service(CutoverState.DUAL_RUNNING)

        cache.clearDatabaseForRescan()
        coVerify(exactly = 1) { displayDao.deleteAll() }

        walletReset.emit(Unit)
        coVerify(timeout = 5_000, exactly = 2) { displayDao.deleteAll() }
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

        cache.setFilter(TxFilterType.SENT)
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }

        cache.setFilter(TxFilterType.RECEIVED)
        awaitTrue { createdPagingSources[1].invalid }
        awaitTrue { createdPagingSources.size >= 3 }

        coVerify(exactly = 1) { groupDao.getActiveGroups(any()) }
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
        assertSame(walletB, cache.coinJoinWrapperFactory()?.wallet)
        awaitTrue { createdPagingSources[0].invalid }
        awaitTrue { createdPagingSources.size >= 2 }
    }
}
