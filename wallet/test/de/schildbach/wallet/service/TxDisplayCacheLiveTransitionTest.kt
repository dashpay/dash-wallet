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

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TxInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * MO-1109 round 2 (pre-review): a filter pick that lands while the very first
 * (no-cache) load is still suspended inside [TxDisplayCacheService.rebuildWrappedList]
 * used to strand `_txDataSource` at `Empty` forever, because the `else` branch of the
 * `_currentFilter`/wallet `flatMapLatest` treated `wrappedTransactionList` being
 * non-empty as proof that `_txDataSource` had already reached `RoomLive` — an
 * assumption `flatMapLatest` cancellation breaks, since `wrappedTransactionList` is
 * assigned synchronously but the `RoomLive` transition only happens after the
 * suspending group-cache/display-cache writes that follow it. Pins the fix: the
 * `else` branch now re-asserts `RoomLive` itself rather than assuming it already
 * happened. Fails on the pre-fix code (where `transactions` would stay empty for
 * the rest of the session) and passes after it.
 */
class TxDisplayCacheLiveTransitionTest {

    private val enteredRebuild = CompletableDeferred<Unit>()
    private val groupCacheWrite = CompletableDeferred<Unit>()

    private val groupDao = mockk<TxGroupCacheDao>(relaxed = true)
    private val displayDao = mockk<TxDisplayCacheDao>(relaxed = true)
    private var service: TxDisplayCacheService? = null

    private class FakeWrapper(
        override val id: String,
        override val groupDate: LocalDate
    ) : TransactionWrapper {
        override val transactions = hashMapOf(
            "txid1" to TxInfo(txId = "txid1", updateTimeMillis = System.currentTimeMillis())
        )
        override fun tryInclude(tx: TxInfo) = false
        override fun getValue() = Dash.ZERO
    }

    @After
    fun tearDown() {
        service?.serviceScope?.cancel()
    }

    @Test
    fun filterChangeDuringTheFirstColdRebuildDoesNotStrandTheLiveDataSource() = runBlocking {
        coEvery { displayDao.getCount() } returns 0
        coEvery { displayDao.getAll() } returns emptyList()
        // persistGroupCache's write is where the fix's "else" branch has to recover from:
        // the suspension that flatMapLatest will cancel once the filter changes.
        coEvery { groupDao.replaceAll(any()) } coAnswers {
            enteredRebuild.complete(Unit)
            groupCacheWrite.await()
        }

        val fakeWallet = mockk<WalletEx>(relaxed = true)
        val walletData = mockk<WalletData>(relaxed = true) {
            every { wallet } returns fakeWallet
            every { observeWallet() } returns flowOf(fakeWallet)
            every { wrapAllTransactions(any(), any()) } returns listOf(FakeWrapper("g1", LocalDate.now()))
            every { observeTransactions(any(), *anyVararg()) } returns emptyFlow()
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

        val cache = TxDisplayCacheService(
            walletData = walletData,
            walletApplication = mockk<WalletApplication>(relaxed = true),
            txDisplayCacheDao = displayDao,
            txGroupCacheDao = groupDao,
            metadataProvider = metadata,
            platformRepo = mockk<PlatformRepo>(relaxed = true),
            identityRepo = identityRepo,
            blockchainStateProvider = blockchainState,
            displayCacheRefreshBus = DisplayCacheRefreshBus(),
            dashPayConfig = mockk<DashPayConfig>(relaxed = true),
            cutoverUiDataService = { mockk<CutoverUiDataService>(relaxed = true) },
            l1SyncStatusService = { mockk<L1SyncStatusService>(relaxed = true) }
        ).also { service = it }

        // Let the first (cold, no-cache) rebuild run up to — and suspend inside —
        // the group-cache write. By this point `wrappedTransactionList` is already
        // set (it is assigned synchronously, earlier in rebuildWrappedList).
        withTimeout(5_000) { enteredRebuild.await() }

        // A filter pick now cancels the still-suspended rebuild via flatMapLatest
        // and re-enters the flatMapLatest block; wrappedTransactionList.isEmpty()
        // is false, so this takes the "switch filter flag only" else branch.
        cache.setFilter(TxFilterType.SENT)

        // Let the (now-cancelled) original write unblock, in case anything is
        // still waiting on it.
        groupCacheWrite.complete(Unit)

        val dataSourceField = TxDisplayCacheService::class.java.getDeclaredField("_txDataSource").apply {
            isAccessible = true
        }
        withTimeout(5_000) {
            var dataSourceClassName = ""
            while (dataSourceClassName != "RoomLive") {
                val stateFlow = dataSourceField.get(cache) as kotlinx.coroutines.flow.StateFlow<*>
                dataSourceClassName = stateFlow.value!!.javaClass.simpleName
                if (dataSourceClassName != "RoomLive") delay(10)
            }
            assertEquals("RoomLive", dataSourceClassName)
        }
    }
}
