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

package de.schildbach.wallet.ui

import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.AssetLockKindResolver
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.service.platform.sdk.L1TxUiDirection
import de.schildbach.wallet.service.platform.sdk.L1TxUiStatus
import de.schildbach.wallet.service.platform.sdk.SdkTopUpRecoveryService
import de.schildbach.wallet.service.platform.sdk.SdkTxDetail
import de.schildbach.wallet.service.platform.sdk.SdkTxDetailProvider
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.integrations.maya.data.SwapOrderDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DW-1659 review round (Andrei): the OUTGOING recipient list [SdkTxDetail]
 * withholds while [SdkTxDetail.recipientsPendingMirror] is true must refresh
 * once ownership resolves, driven by [SdkTxDetailProvider.observeTxoCountChanges]
 * — never a poll timer. This pins the SAME [TransactionResultViewModel]
 * instance picking up the real recipient after that Flow fires, with no
 * second init()/screen reopen involved.
 *
 * DW-1659 review round 3 (Andrei, finding 4): [TransactionResultViewModel.init]
 * and [TransactionResultViewModel.observeUntilRecipientsMirrored] hop through
 * the real [Dispatchers.IO] thread pool — there is no test-controlled
 * dispatcher for that hop, so a fixed [Thread.sleep]-poll could sample in
 * between two already-completed loads and never observe the intermediate
 * withheld state. [readyToObserve] below is the actual fix: an explicit,
 * test-held gate on `observeTxoCountChanges()` (the one call that starts the
 * race) so the collector cannot even subscribe — let alone consume the
 * pre-seeded replay value and trigger the second load — until this test has
 * already asserted the withheld state. Every other wait is a suspend on a
 * real [kotlinx.coroutines.flow.StateFlow]/[MutableSharedFlow.subscriptionCount]
 * via [realTime], never a sleep-based poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionResultViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var activeViewModel: TransactionResultViewModel? = null

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // Cancels observeUntilRecipientsMirrored's collector job (if any is
        // still running) before resetMain() — otherwise it can resume on a
        // later test's thread with no Main dispatcher installed.
        activeViewModel?.viewModelScope?.cancel()
        activeViewModel = null
        Dispatchers.resetMain()
    }

    private val txId = Sha256Hash.wrap("ab".repeat(32))
    private val recipientAddress = "yRecipientAddressForDW1659TestXX"

    /** The change output's TXO mirror row hasn't landed — OUTGOING withholds. */
    private fun withheldDetail() = SdkTxDetail(
        txIdDisplayHex = txId.toString(),
        netAmountDuffs = -70_247L,
        feeDuffs = 247L,
        timestampMs = 1_770_000_000_000L,
        status = L1TxUiStatus.IN_BLOCK,
        direction = L1TxUiDirection.OUTGOING,
        inputAddresses = emptyList(),
        outputAddresses = emptyList(),
        hasOpReturn = false,
        recipientsPendingMirror = true
    )

    /** The same tx, reloaded after the mirror row landed. */
    private fun resolvedDetail() = withheldDetail().copy(
        outputAddresses = listOf(recipientAddress),
        recipientsPendingMirror = false
    )

    /**
     * Awaits [block] on a real (non-virtual) dispatcher. [withTimeout] inside
     * [runTest]'s own TestScope measures virtual time, which never advances
     * on its own while this coroutine is suspended waiting on a REAL
     * background thread (the ViewModel's genuine `withContext(Dispatchers.IO)`
     * hops) — so a virtual-time timeout would fire immediately instead of
     * actually waiting. Moving to a real dispatcher first gives the timeout
     * real wall-clock semantics.
     */
    private suspend fun <T> realTime(timeoutMs: Long = 5_000, block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(timeoutMs) { block() } }

    private fun viewModel(
        sdkTxDetailProvider: SdkTxDetailProvider,
        walletData: WalletData = mockk<WalletData> {
            every { wallet } returns mockk<Wallet> { every { getTransaction(txId) } returns null }
        },
        transactionMetadataProvider: TransactionMetadataProvider = mockk<TransactionMetadataProvider> {
            coEvery { importTransactionMetadata(any()) } just Runs
            every { observeTransactionMetadata(any()) } returns flowOf<TransactionMetadata?>(null)
        }
    ) = TransactionResultViewModel(
        transactionMetadataProvider = transactionMetadataProvider,
        giftCardDao = mockk<GiftCardDao>(relaxed = true),
        walletData = walletData,
        swapOrderDao = mockk<SwapOrderDao>(relaxed = true),
        configuration = mockk<Configuration>(relaxed = true),
        dashPayProfileDao = mockk<DashPayProfileDao>(relaxed = true),
        topUpsDao = mockk<TopUpsDao>(relaxed = true),
        identityRepository = mockk<IdentityRepository>(relaxed = true),
        platformRepo = mockk<PlatformRepo>(relaxed = true),
        sdkTxDetailProvider = sdkTxDetailProvider,
        cutoverUiDataService = mockk<CutoverUiDataService>(relaxed = true),
        assetLockKindResolver = mockk<AssetLockKindResolver>(relaxed = true),
        sdkTopUpRecoveryService = mockk<SdkTopUpRecoveryService>(relaxed = true),
        analytics = mockk<AnalyticsService>(relaxed = true),
        walletApplication = mockk<WalletApplication>(relaxed = true)
    ).also { activeViewModel = it }

    @Test
    fun sdkTxDetail_refreshesOnTxoCountChange_untilRecipientsResolve() = runTest(dispatcher) {
        // The TXO count flow the ViewModel observes instead of polling —
        // Room's own count query re-emits on every table invalidation, so a
        // real "row inserted" event looks exactly like a new value here.
        // A replaying SharedFlow (not a plain StateFlow), pre-seeded with the
        // POST-mirror count BEFORE the collector subscribes: the initial
        // loadSdkDetailOrNull() and the collector's subscription are two
        // separate, non-atomic operations in production, and a mirror write
        // can land in that window — the collector's very first (replayed)
        // emission must still be reloaded, not dismissed as an already-seen
        // baseline (06b-prereview.md finding 2).
        val txoCounts = MutableSharedFlow<Long>(replay = 1).apply { tryEmit(1L) }
        val loadCalls = mutableListOf<String>()
        // The actual race finding 4 pins: without this gate, nothing stops
        // the collector from subscribing and consuming the pre-seeded replay
        // value (triggering the second load) before this test ever observes
        // the first, withheld one. Held until the withheld state is asserted.
        val readyToObserve = CompletableDeferred<Unit>()
        val sdkTxDetailProvider = mockk<SdkTxDetailProvider> {
            coEvery { load(any()) } answers {
                loadCalls += firstArg<String>()
                if (loadCalls.size == 1) withheldDetail() else resolvedDetail()
            }
            coEvery { observeTxoCountChanges() } coAnswers {
                readyToObserve.await()
                txoCounts
            }
        }

        val viewModel = viewModel(sdkTxDetailProvider)
        viewModel.init(txId)

        // First load: the change TXO is missing — recipients withheld. The
        // collector cannot have run ahead of this: observeTxoCountChanges()
        // is still parked on readyToObserve.
        realTime { viewModel.sdkTxDetail.first { it != null } }
        assertEquals(emptyList<String>(), viewModel.sdkTxDetail.value?.outputAddresses)
        assertTrue(viewModel.sdkTxDetail.value?.recipientsPendingMirror == true)
        assertEquals(1, loadCalls.size)

        // Release the gate: the collector can now fetch the flow, subscribe,
        // and consume the already-replayed value.
        readyToObserve.complete(Unit)
        realTime { txoCounts.subscriptionCount.first { it >= 1 } }

        // The SAME ViewModel now shows only the real recipient, reloaded off
        // the collector's first (replayed) emission — no second table write
        // needed.
        realTime { viewModel.sdkTxDetail.first { it?.recipientsPendingMirror == false } }
        assertEquals(listOf(recipientAddress), viewModel.sdkTxDetail.value?.outputAddresses)
        assertEquals(2, loadCalls.size)

        // takeWhile stopped the collector — a further table change must not
        // trigger a third reload. There is no further event to suspend on,
        // so this is a bounded real-time check for that absence.
        txoCounts.tryEmit(2L)
        realTime(200) { delay(100) }
        assertEquals(2, loadCalls.size)
    }

    @Test
    fun sdkTxDetail_ordinaryResolvedSend_neverObservesTxoCounts() = runTest(dispatcher) {
        // recipientsPendingMirror == false from the first load: nothing to
        // watch for, and observeTxoCountChanges must not even be consulted.
        val sdkTxDetailProvider = mockk<SdkTxDetailProvider> {
            coEvery { load(any()) } returns resolvedDetail()
            coEvery { observeTxoCountChanges() } throws AssertionError(
                "must not be called when the first load already resolved the recipient"
            )
        }

        val viewModel = viewModel(sdkTxDetailProvider)
        viewModel.init(txId)

        realTime { viewModel.sdkTxDetail.first { it != null } }
        assertEquals(listOf(recipientAddress), viewModel.sdkTxDetail.value?.outputAddresses)
    }
}
