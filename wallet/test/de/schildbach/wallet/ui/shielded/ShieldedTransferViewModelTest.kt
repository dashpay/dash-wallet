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

package de.schildbach.wallet.ui.shielded

import android.content.Context
import de.schildbach.wallet.payments.ChainLockedCoinSelector
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.L1VerificationStatus
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.service.platform.sdk.ShieldFromWalletOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.CoinSelector
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NotificationService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.EnumSet

/**
 * Verifies the SdkWriteResult → UI contract of [ShieldedTransferViewModel]:
 * Broadcast → Success, NotBroadcast → NotSent (retryable), Ambiguous →
 * MayHaveGoneThrough (terminal — the UI never offers a retry), plus the
 * amount/balance/sync gating of the Continue button, the chainlocked-only
 * wallet balance wiring and the first-visit timing-sheet logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedTransferViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // READY / gate-open by default so the pre-existing tests exercise their
    // own gates; the pool-readiness and funding-gate tests drive these
    // flows directly (both are observed live, not read once).
    private val poolSyncStatus = MutableStateFlow(ShieldedSyncStatus.READY)
    private val walletShieldingAvailable = MutableStateFlow(true)
    private val shieldedService = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns true
        every { observeWalletShieldingAvailable() } returns walletShieldingAvailable
        every { observeShieldedBalance() } returns flowOf(Dash.parse("15.5"))
        every { shieldedSyncStatus } returns poolSyncStatus
    }
    private val walletData = mockk<WalletDataProvider> {
        // total balance only feeds the "pending" explainer; the
        // transferable balance comes from the chainlocked-only selection
        every { observeTotalBalance() } returns flowOf(Coin.parseCoin("3.00"))
        every { observeBalance(any(), any()) } returns flowOf(Coin.parseCoin("3.00"))
        every { freshReceiveAddressString() } returns "yTestAddressBase58"
    }
    private val dashPayConfig = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN) } returns true
        coEvery { set(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN, any()) } returns Unit
    }
    private val blockchainStateProvider = mockk<BlockchainStateProvider> {
        every { observeState() } returns flowOf(blockchainState(synced = true))
    }
    private val walletUIConfig = mockk<WalletUIConfig> {
        every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns flowOf("USD")
    }
    private val exchangeRates = mockk<ExchangeRatesProvider> {
        every { observeExchangeRate(any()) } returns flowOf(null)
    }
    private val shadowVerificationStatus = MutableStateFlow(L1VerificationStatus.UNKNOWN)
    private val shadowProgress = MutableStateFlow(ShadowSyncProgress.IDLE)
    private val l1ShadowSyncService = mockk<L1ShadowSyncService> {
        every { verificationStatus } returns shadowVerificationStatus
        every { progress } returns shadowProgress
    }

    private fun blockchainState(
        synced: Boolean,
        bestChainHeight: Int = 1_000,
        chainlockHeight: Int = 990,
        // a mid-sync wallet's chain tip is in the past; a synced one is current
        bestChainDate: Date = if (synced) Date() else Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L),
        percentageSync: Int = if (synced) 100 else 42,
        replaying: Boolean = false,
        impediments: EnumSet<BlockchainState.Impediment> =
            EnumSet.noneOf(BlockchainState.Impediment::class.java)
    ) = BlockchainState(
        bestChainDate,
        bestChainHeight,
        replaying,
        impediments,
        chainlockHeight,
        chainlockHeight,
        percentageSync
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A real app-scoped [ShieldedTransferExecutor] over the mocks — the
     * ViewModel only mirrors its state, so the submit tests exercise the
     * genuine wiring. Foreground + transfer-screen-visible: the executor
     * announces nothing, the screen state machine is what's under test.
     */
    private fun executor(): ShieldedTransferExecutor =
        ShieldedTransferExecutor(
            shieldedService,
            walletData,
            mockk<NotificationService>(relaxUnitFun = true),
            mockk<Context> { every { getString(any()) } returns "" },
            CoroutineScope(dispatcher)
        ).apply {
            ioDispatcher = dispatcher
            isAppInForeground = { true }
            transferUiVisible = true
            moreScreenIntent = { null }
        }

    private fun viewModel(
        transferExecutor: ShieldedTransferExecutor = executor()
    ): ShieldedTransferViewModel =
        ShieldedTransferViewModel(
            shieldedService,
            walletData,
            dashPayConfig,
            blockchainStateProvider,
            walletUIConfig,
            exchangeRates,
            l1ShadowSyncService,
            transferExecutor
        )

    private fun ShieldedTransferViewModel.typeAmountAndConfirm(amount: String = "1") {
        amount.forEach { onKeyInput(it.toString()) }
        onContinue()
        onConfirm()
    }

    @Test
    fun continueGating_amountAndBalance() = runTest(dispatcher) {
        val vm = viewModel()

        // zero amount — blocked
        assertFalse(vm.uiState.value.canContinue)

        // 1 DASH of a 3 DASH wallet balance — allowed
        vm.onKeyInput("1")
        assertTrue(vm.uiState.value.canContinue)

        // 100 DASH — insufficient funds, blocked, flagged
        vm.onKeyInput("0")
        vm.onKeyInput("0")
        assertTrue(vm.uiState.value.insufficientFunds)
        assertFalse(vm.uiState.value.canContinue)
    }

    @Test
    fun swapDirection_switchesAvailableBalance() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(Dash.parse("3.00"), vm.uiState.value.availableBalance)

        vm.onSwapDirection()
        assertEquals(ShieldedTransferDirection.FromShielded, vm.uiState.value.direction)
        assertEquals(Dash.parse("15.5"), vm.uiState.value.availableBalance)
    }

    @Test
    fun toShielded_usesShieldFromWallet_andMapsToSuccess() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        val vm = viewModel()

        vm.typeAmountAndConfirm()

        assertEquals(ShieldedSubmitState.Success, vm.uiState.value.submitState)
        // "Dash Wallet → Shielded" spends the L1 balance via the asset-lock
        // pipeline — never the credits-based Type 15.
        io.mockk.coVerify { shieldedService.shieldFromWallet(Dash.parse("1")) }
        io.mockk.coVerify(exactly = 0) { shieldedService.shieldFromCredits(any()) }
    }

    @Test
    fun toShielded_lockPendingRetry_mapsToTerminalLockedPendingShield() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        val vm = viewModel()

        vm.typeAmountAndConfirm()

        val state = vm.uiState.value
        assertEquals(ShieldedSubmitState.LockedPendingShield, state.submitState)
        // terminal: the L1 lock is out, so no manual retry is ever offered
        assertFalse(state.canContinue)
        vm.onKeyInput("9")
        assertEquals("1", vm.uiState.value.amountText)
    }

    @Test
    fun notBroadcast_mapsToNotSent_andAllowsRetry() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.NotBroadcast("preflight failed")
        val vm = viewModel()

        vm.typeAmountAndConfirm()

        val state = vm.uiState.value
        assertTrue(state.submitState is ShieldedSubmitState.NotSent)
        // NotBroadcast is provably pre-broadcast: retry stays available
        assertTrue(state.canContinue)
    }

    @Test
    fun ambiguous_mapsToTerminalMayHaveGoneThrough() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        val vm = viewModel()

        vm.typeAmountAndConfirm()

        val state = vm.uiState.value
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, state.submitState)
        // terminal: no retry — Continue is blocked and keypad input is ignored
        assertFalse(state.canContinue)
        vm.onKeyInput("9")
        assertEquals("1", vm.uiState.value.amountText)
    }

    // ── App-scoped execution: re-attach, dismissal, no re-submit ────────

    @Test
    fun recreatedViewModel_reattachesToInFlightOp_andCanNeverResubmit() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val transferExecutor = executor()

        val vm1 = viewModel(transferExecutor)
        vm1.typeAmountAndConfirm()
        assertEquals(ShieldedSubmitState.Proving, vm1.uiState.value.submitState)

        // The screen (and its ViewModel) dies mid-proof; a recreated one
        // re-attaches to the SAME in-flight state — the proving UI shows
        // again instead of a blank submittable form.
        val vm2 = viewModel(transferExecutor)
        assertEquals(ShieldedSubmitState.Proving, vm2.uiState.value.submitState)
        assertTrue(vm2.uiState.value.transferInFlight)
        assertFalse(vm2.uiState.value.canContinue)

        // Even a full user gesture sequence on the recreated screen must
        // not spend again while the op is in flight (funds safety).
        vm2.typeAmountAndConfirm()
        // …nor may a fresh screen entry drop the in-flight op
        vm2.reset()
        assertEquals(ShieldedSubmitState.Proving, vm2.uiState.value.submitState)

        gate.complete(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        assertEquals(ShieldedSubmitState.Success, vm2.uiState.value.submitState)
        coVerify(exactly = 1) { shieldedService.shieldFromWallet(any()) }
    }

    @Test
    fun dismissProving_hidesTheDialogOnly_untilTheOpFinishes() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val vm = viewModel()

        vm.typeAmountAndConfirm()
        assertEquals(ShieldedSubmitState.Proving, vm.uiState.value.submitState)
        assertFalse(vm.uiState.value.provingDismissed)

        // Dismiss hides the modal; the spend keeps running and the screen
        // stays in the in-flight state (Continue is the in-progress button).
        vm.onDismissProving()
        assertTrue(vm.uiState.value.provingDismissed)
        assertEquals(ShieldedSubmitState.Proving, vm.uiState.value.submitState)
        assertFalse(vm.uiState.value.canContinue)

        // The dismissal is per-operation: it resets once the op finishes.
        gate.complete(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        assertEquals(ShieldedSubmitState.Success, vm.uiState.value.submitState)
        assertFalse(vm.uiState.value.provingDismissed)
    }

    @Test
    fun dismissProving_isIgnoredWhileNothingIsInFlight() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDismissProving()
        assertFalse(vm.uiState.value.provingDismissed)
    }

    // The stall watchdog: a wedged spend (uncancellable native frame)
    // surfaces as Stalled after the threshold, and the screen treats it
    // as in-flight — Continue stays locked (a retry could double-submit
    // once the wedge clears), and only a real terminal outcome unlocks.
    @Test
    fun stalled_keepsContinueLocked_untilATerminalResultSupersedes() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val vm = viewModel()

        vm.typeAmountAndConfirm()
        assertEquals(ShieldedSubmitState.Proving, vm.uiState.value.submitState)

        advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS)
        runCurrent()
        assertEquals(ShieldedSubmitState.Stalled(), vm.uiState.value.submitState)
        // funds safety: amount + gates are all green, only Stalled blocks
        assertFalse(vm.uiState.value.canContinue)

        // dismissing the stalled overlay must not unlock the screen —
        // the state only flips to acknowledged and stays non-resubmittable
        vm.onResultHandled()
        assertEquals(
            ShieldedSubmitState.Stalled(acknowledged = true),
            vm.uiState.value.submitState
        )
        assertFalse(vm.uiState.value.canContinue)

        // the wedged spend finally returns: its real outcome supersedes
        gate.complete(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        assertEquals(ShieldedSubmitState.Success, vm.uiState.value.submitState)
        coVerify(exactly = 1) { shieldedService.shieldFromWallet(any()) }
    }

    // The background status toast must freeze while a modal overlay is up
    // or a submit is in flight (live feedback: the "Verifying your
    // balance…" line kept updating behind the proving dialog).
    @Test
    fun blockedToast_suppressedWhileModalOrInFlight() {
        // FUNDING_PENDING blocked reason present throughout
        val blocked = ShieldedTransferUIState(
            ready = true,
            readyCheckDone = true,
            chainSynced = true,
            shieldedSyncStatus = ShieldedSyncStatus.READY,
            walletShieldingAvailable = false
        )
        assertEquals(ShieldedBlockedReason.FUNDING_PENDING, blocked.blockedReason)

        // idle form → the toast may show
        assertFalse(blocked.blockedToastSuppressed)
        // …and an inline retryable error keeps it visible too
        assertFalse(blocked.copy(submitState = ShieldedSubmitState.NotSent("x")).blockedToastSuppressed)

        // proving — dialog up OR dismissed — suppresses it
        assertTrue(blocked.copy(submitState = ShieldedSubmitState.Proving).blockedToastSuppressed)
        assertTrue(
            blocked.copy(submitState = ShieldedSubmitState.Proving, provingDismissed = true)
                .blockedToastSuppressed
        )

        // any other modal overlay suppresses it as well
        assertTrue(blocked.copy(showConfirm = true).blockedToastSuppressed)
        assertTrue(blocked.copy(showTimingInfo = true).blockedToastSuppressed)
        assertTrue(blocked.copy(submitState = ShieldedSubmitState.Success).blockedToastSuppressed)
        assertTrue(
            blocked.copy(submitState = ShieldedSubmitState.MayHaveGoneThrough).blockedToastSuppressed
        )
        assertTrue(
            blocked.copy(submitState = ShieldedSubmitState.LockedPendingShield).blockedToastSuppressed
        )
    }

    @Test
    fun walletShieldingUnavailable_blocksToShielded_butNotFromShielded() = runTest(dispatcher) {
        walletShieldingAvailable.value = false
        val vm = viewModel()

        vm.onKeyInput("1")
        // The L1 funding gate is closed: Dash Wallet → Shielded is blocked…
        assertFalse(vm.uiState.value.canContinue)

        // …but Shielded → Dash Wallet does not need the gate.
        vm.onSwapDirection()
        assertTrue(vm.uiState.value.canContinue)
    }

    @Test
    fun walletShieldingBecomingAvailable_unblocksLive_withoutReentry() = runTest(dispatcher) {
        // Screen opened while the shadow-sync verification is still pending:
        // the funding gate is closed, so the "Verifying your balance…"
        // toast (FUNDING_PENDING) shows and Dash Wallet → Shielded is blocked.
        walletShieldingAvailable.value = false
        val vm = viewModel()

        vm.onKeyInput("1")
        assertFalse(vm.uiState.value.walletShieldingAvailable)
        assertEquals(ShieldedBlockedReason.FUNDING_PENDING, vm.uiState.value.blockedReason)
        assertFalse(vm.uiState.value.canContinue)

        // The harness reaches a fresh parity MATCH while the screen is still
        // open (UIState was already collected once above). The gate must
        // re-open by itself — proving the field is OBSERVED, not a one-shot
        // snapshot read at init (the live bug: it stayed stuck until re-entry).
        walletShieldingAvailable.value = true
        assertTrue(vm.uiState.value.walletShieldingAvailable)
        assertNull(vm.uiState.value.blockedReason)
        assertTrue(vm.uiState.value.canContinue)
    }

    @Test
    fun fromShielded_usesWithdrawToCore_withFreshAddress() = runTest(dispatcher) {
        coEvery { shieldedService.withdrawToCore(any(), any()) } returns SdkWriteResult.Broadcast(Unit)
        val vm = viewModel()

        vm.onSwapDirection()
        vm.typeAmountAndConfirm()

        assertEquals(ShieldedSubmitState.Success, vm.uiState.value.submitState)
        io.mockk.coVerify {
            shieldedService.withdrawToCore("yTestAddressBase58", Dash.parse("1"))
        }
    }

    @Test
    fun freshAddressFailure_isNotSent_notAmbiguous() = runTest(dispatcher) {
        every { walletData.freshReceiveAddressString() } throws IllegalStateException("wallet locked")
        val vm = viewModel()

        vm.onSwapDirection()
        vm.typeAmountAndConfirm()

        // pre-broadcast failure must surface as NotSent (retry-safe), never Ambiguous
        assertTrue(vm.uiState.value.submitState is ShieldedSubmitState.NotSent)
    }

    // ── AC3: L1 sync gate ───────────────────────────────────────────────

    @Test
    fun chainNotSynced_blocksBothDirections() = runTest(dispatcher) {
        every { blockchainStateProvider.observeState() } returns
            flowOf(blockchainState(synced = false))
        val vm = viewModel()

        vm.onKeyInput("1")
        assertFalse(vm.uiState.value.chainSynced)
        assertFalse(vm.uiState.value.canContinue)

        // 'pending design' per AC: the gate applies to BOTH directions
        vm.onSwapDirection()
        assertFalse(vm.uiState.value.canContinue)
    }

    @Test
    fun chainSynced_unblocksContinue() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onKeyInput("1")
        assertTrue(vm.uiState.value.chainSynced)
        assertTrue(vm.uiState.value.canContinue)
    }

    @Test
    fun missingBlockchainState_staysConservativelyBlocked() = runTest(dispatcher) {
        every { blockchainStateProvider.observeState() } returns flowOf(null)
        val vm = viewModel()

        vm.onKeyInput("1")
        assertFalse(vm.uiState.value.chainSynced)
        assertFalse(vm.uiState.value.canContinue)
    }

    @Test
    fun idleSyncedAfterRestart_passesSyncGate() = runTest(dispatcher) {
        // The realistic post-restart state of an already-synced wallet:
        // WalletApplication.onCreate resets the persisted percentageSync
        // to 0, and only a fresh peer connection (the download tracker's
        // progress/doneDownload) ever sets it back to 100 — but the chain
        // tip itself is current. The gate must not lock this wallet out.
        every { blockchainStateProvider.observeState() } returns flowOf(
            blockchainState(synced = true, percentageSync = 0, bestChainDate = Date())
        )
        val vm = viewModel()

        vm.onKeyInput("1")
        assertTrue(vm.uiState.value.chainSynced)
        assertTrue(vm.uiState.value.canContinue)
    }

    // isChainSyncedForTransfer is the pure gate predicate — pin its edges
    // with an explicit clock.
    @Test
    fun chainSyncGatePredicate_edges() {
        val now = 1_800_000_000_000L
        val fresh = Date(now - 5 * 60 * 1000L) // tip 5 min old
        val stale = Date(now - 2 * 60 * 60 * 1000L) // tip 2 h old

        // canonical isSynced() passes regardless of tip age
        assertTrue(blockchainState(synced = true, bestChainDate = stale).isChainSyncedForTransfer(now))

        // idle-synced after restart: percentage reset to 0, fresh tip → passes
        assertTrue(
            blockchainState(synced = true, percentageSync = 0, bestChainDate = fresh)
                .isChainSyncedForTransfer(now)
        )

        // genuinely mid-sync: stale tip, partial percentage → blocked
        assertFalse(
            blockchainState(synced = false, percentageSync = 42, bestChainDate = stale)
                .isChainSyncedForTransfer(now)
        )

        // fresh tip does NOT excuse replaying or a network impediment
        assertFalse(
            blockchainState(synced = true, percentageSync = 0, bestChainDate = fresh, replaying = true)
                .isChainSyncedForTransfer(now)
        )
        assertFalse(
            blockchainState(
                synced = true,
                percentageSync = 0,
                bestChainDate = fresh,
                impediments = EnumSet.of(BlockchainState.Impediment.NETWORK)
            ).isChainSyncedForTransfer(now)
        )

        // no persisted state / no tip date yet → conservatively blocked
        assertFalse((null as BlockchainState?).isChainSyncedForTransfer(now))
        assertFalse(
            BlockchainState(/* replaying = */ false).isChainSyncedForTransfer(now)
        )
    }

    // ── Shielded-pool readiness gate (live Ambiguous failures happened
    //    while the pool was still SYNCING after app start) ───────────────

    @Test
    fun poolNotReady_blocksBothDirections() = runTest(dispatcher) {
        poolSyncStatus.value = ShieldedSyncStatus.NOT_READY
        val vm = viewModel()

        vm.onKeyInput("1")
        assertFalse(vm.uiState.value.shieldedPoolReady)
        // Dash Wallet → Shielded: the shield transition must not race a stale pool
        assertFalse(vm.uiState.value.canContinue)

        // Shielded → Dash Wallet: spending notes needs up-to-date notes + anchors
        vm.onSwapDirection()
        assertFalse(vm.uiState.value.canContinue)
    }

    @Test
    fun poolSyncing_blocksBothDirections() = runTest(dispatcher) {
        poolSyncStatus.value = ShieldedSyncStatus.SYNCING
        val vm = viewModel()

        vm.onKeyInput("1")
        assertFalse(vm.uiState.value.shieldedPoolReady)
        assertFalse(vm.uiState.value.canContinue)

        vm.onSwapDirection()
        assertFalse(vm.uiState.value.canContinue)
    }

    @Test
    fun poolBecomingReady_unblocksLive_withoutReentry() = runTest(dispatcher) {
        poolSyncStatus.value = ShieldedSyncStatus.SYNCING
        val vm = viewModel()

        vm.onKeyInput("1")
        assertFalse(vm.uiState.value.canContinue)

        // The first sync pass lands while the screen is open: the gate
        // opens by itself — both directions.
        poolSyncStatus.value = ShieldedSyncStatus.READY
        assertTrue(vm.uiState.value.shieldedPoolReady)
        assertTrue(vm.uiState.value.canContinue)

        vm.onSwapDirection()
        assertTrue(vm.uiState.value.canContinue)
    }

    // blockedReason is the pure priority mapping behind the blocked-state
    // toast (the composable only picks the string per reason) — pin it.
    @Test
    fun blockedReason_toastMapping() {
        val allGreen = ShieldedTransferUIState(
            ready = true,
            readyCheckDone = true,
            chainSynced = true,
            shieldedSyncStatus = ShieldedSyncStatus.READY,
            walletShieldingAvailable = true
        )
        // nothing blocks → no toast
        assertNull(allGreen.blockedReason)

        // before the first readiness check finishes, never flash a toast
        assertNull(allGreen.copy(readyCheckDone = false, ready = false).blockedReason)

        // chain/runtime sync outranks everything
        assertEquals(
            ShieldedBlockedReason.CHAIN_SYNCING,
            allGreen.copy(chainSynced = false, shieldedSyncStatus = ShieldedSyncStatus.SYNCING)
                .blockedReason
        )
        assertEquals(ShieldedBlockedReason.CHAIN_SYNCING, allGreen.copy(ready = false).blockedReason)

        // pool sync outranks the funding gate and hits BOTH directions
        for (status in listOf(ShieldedSyncStatus.NOT_READY, ShieldedSyncStatus.SYNCING)) {
            assertEquals(
                ShieldedBlockedReason.POOL_SYNCING,
                allGreen.copy(shieldedSyncStatus = status, walletShieldingAvailable = false)
                    .blockedReason
            )
            assertEquals(
                ShieldedBlockedReason.POOL_SYNCING,
                allGreen.copy(
                    shieldedSyncStatus = status,
                    direction = ShieldedTransferDirection.FromShielded
                ).blockedReason
            )
        }

        // funding gate: only the ToShielded direction, only once all synced
        assertEquals(
            ShieldedBlockedReason.FUNDING_PENDING,
            allGreen.copy(walletShieldingAvailable = false).blockedReason
        )
        assertNull(
            allGreen.copy(
                walletShieldingAvailable = false,
                direction = ShieldedTransferDirection.FromShielded
            ).blockedReason
        )
    }

    // ── AC4/AC6: chainlocked-only wallet balance ────────────────────────

    @Test
    fun walletBalance_comesFromChainLockedSelection_notTotal() = runTest(dispatcher) {
        val selector = slot<CoinSelector>()
        every { walletData.observeBalance(any(), capture(selector)) } returns
            flowOf(Coin.parseCoin("2.00")) // chainlocked-only < 3.00 total
        val vm = viewModel()

        // display AND validation use the chainlocked-only balance
        assertEquals(Dash.parse("2.00"), vm.uiState.value.walletBalance)
        assertEquals(Dash.parse("2.00"), vm.uiState.value.availableBalance)
        assertTrue(selector.captured is ChainLockedCoinSelector)

        // Max fills the chainlocked-only amount, never the total
        vm.onMaxClick()
        assertEquals("2", vm.uiState.value.amountText)

        // an amount inside the total but above the chainlocked part is rejected
        vm.onKeyInput("back_long")
        "2.5".forEach { vm.onKeyInput(it.toString()) }
        assertTrue(vm.uiState.value.insufficientFunds)
        assertFalse(vm.uiState.value.canContinue)
    }

    @Test
    fun pendingWalletBalance_isTotalMinusChainlocked_clampedAtZero() {
        val base = ShieldedTransferUIState(
            walletBalance = Dash.parse("2.00"),
            totalWalletBalance = Dash.parse("3.25")
        )
        assertEquals(Dash.parse("1.25"), base.pendingWalletBalance)

        // everything chainlocked → nothing pending
        assertEquals(
            Dash.ZERO,
            base.copy(totalWalletBalance = Dash.parse("2.00")).pendingWalletBalance
        )

        // transient selector-universe disagreement must clamp, not go negative
        assertEquals(
            Dash.ZERO,
            base.copy(totalWalletBalance = Dash.parse("1.00")).pendingWalletBalance
        )
    }

    // ── "You will transfer ~" hint units (Figma 1746:18462 / 1746:18478) ──

    @Test
    fun transferHint_toShielded_isCredits_grossConversion() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onKeyInput("1")
        val state = vm.uiState.value
        // 1 DASH = 1e8 duffs = 1e11 credits — the design shows
        // "~ 100,000,000,000 C" for a 1 DASH entry.
        assertEquals("~ ${java.text.NumberFormat.getIntegerInstance().format(100_000_000_000L)}", state.transferHintText)
        assertTrue(state.transferHintIsCredits)
    }

    @Test
    fun transferHint_fromShielded_isDash() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onSwapDirection()
        vm.onKeyInput("1")
        val state = vm.uiState.value
        // the design shows "~ 1.00 Đ" for a 1 DASH entry
        assertEquals("~ 1.00", state.transferHintText)
        assertFalse(state.transferHintIsCredits)
    }

    // ── AC2: first-visit timing sheet ───────────────────────────────────

    @Test
    fun firstVisit_autoShowsTimingSheet_andDismissalLatchesTheFlag() = runTest(dispatcher) {
        coEvery { dashPayConfig.get(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN) } returns null
        val vm = viewModel()

        assertTrue(vm.uiState.value.showTimingInfo)

        vm.onTimingInfoDismissed()
        assertFalse(vm.uiState.value.showTimingInfo)
        coVerify { dashPayConfig.set(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN, true) }
    }

    @Test
    fun repeatVisit_doesNotAutoShowTimingSheet_infoIconStillOpensIt() = runTest(dispatcher) {
        coEvery { dashPayConfig.get(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN) } returns true
        val vm = viewModel()

        assertFalse(vm.uiState.value.showTimingInfo)

        // the nav-bar info icon keeps re-opening it manually
        vm.onShowTimingInfo()
        assertTrue(vm.uiState.value.showTimingInfo)
        vm.onTimingInfoDismissed()
        assertFalse(vm.uiState.value.showTimingInfo)
    }

    @Test
    fun timingFlagReadFailure_doesNotAutoShow() = runTest(dispatcher) {
        coEvery { dashPayConfig.get(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN) } throws
            RuntimeException("datastore unavailable")
        val vm = viewModel()

        assertFalse(vm.uiState.value.showTimingInfo)
    }

    // ── Live verification status (funding-gate toast) ───────────────────

    @Test
    fun verificationStatus_flowsFromTheShadowHarnessIntoUiState() = runTest(dispatcher) {
        val vm = viewModel()

        // No harness data → the static fallback copy.
        assertNull(vm.uiState.value.verificationStatus)

        // Scanning with live filter counts.
        shadowVerificationStatus.value = L1VerificationStatus.SCANNING
        shadowProgress.value = ShadowSyncProgress(
            phase = ShadowSyncPhase.FILTERS,
            overallPercent = 79.4,
            headerHeight = 1_511_575, headerTarget = 1_511_575,
            filterHeight = 1_204_511, filterTarget = 1_511_575
        )
        assertEquals(
            ShieldedVerificationStatus.Scanning("1,204,511", "1,511,575", 79),
            vm.uiState.value.verificationStatus
        )

        // Synced, parity being probed → almost done.
        shadowVerificationStatus.value = L1VerificationStatus.PROBING
        assertEquals(ShieldedVerificationStatus.AlmostDone, vm.uiState.value.verificationStatus)

        // Terminal stand-down → failed.
        shadowVerificationStatus.value = L1VerificationStatus.FAILED
        assertEquals(ShieldedVerificationStatus.Failed, vm.uiState.value.verificationStatus)

        // Verified: the gate opens; the toast falls back (and disappears).
        shadowVerificationStatus.value = L1VerificationStatus.VERIFIED
        assertNull(vm.uiState.value.verificationStatus)
    }
}

/**
 * Pure mapping table of [mapVerificationStatus] — the harness state →
 * toast status contract (scanning numbers with digit grouping, almost
 * done, failed, and every fallback-to-static-copy case).
 */
class ShieldedVerificationStatusMappingTest {

    private fun progress(
        phase: ShadowSyncPhase,
        percent: Double = 50.0,
        headerHeight: Long = 0, headerTarget: Long = 0,
        filterHeight: Long = 0, filterTarget: Long = 0
    ) = ShadowSyncProgress(phase, percent, headerHeight, headerTarget, filterHeight, filterTarget)

    private fun map(status: L1VerificationStatus, p: ShadowSyncProgress) =
        mapVerificationStatus(status, p, java.util.Locale.US)

    @Test
    fun scanning_filterPhases_useTheFilterCounts_withGrouping() {
        for (phase in listOf(
            ShadowSyncPhase.FILTER_HEADERS, ShadowSyncPhase.MASTERNODES, ShadowSyncPhase.FILTERS
        )) {
            assertEquals(
                ShieldedVerificationStatus.Scanning("1,204,511", "1,511,575", 79),
                map(
                    L1VerificationStatus.SCANNING,
                    progress(
                        phase, percent = 79.6,
                        headerHeight = 1_511_575, headerTarget = 1_511_575,
                        filterHeight = 1_204_511, filterTarget = 1_511_575
                    )
                )
            )
        }
    }

    @Test
    fun scanning_headerPhase_usesTheHeaderCounts() {
        assertEquals(
            ShieldedVerificationStatus.Scanning("12,345", "1,511,575", 3),
            map(
                L1VerificationStatus.SCANNING,
                progress(
                    ShadowSyncPhase.HEADERS, percent = 3.2,
                    headerHeight = 12_345, headerTarget = 1_511_575
                )
            )
        )
    }

    @Test
    fun scanning_fallsBackToHeaderCounts_whileTheFilterTargetIsUnknown() {
        assertEquals(
            ShieldedVerificationStatus.Scanning("1,511,575", "1,511,575", 60),
            map(
                L1VerificationStatus.SCANNING,
                progress(
                    ShadowSyncPhase.FILTER_HEADERS, percent = 60.0,
                    headerHeight = 1_511_575, headerTarget = 1_511_575
                )
            )
        )
    }

    @Test
    fun scanning_withoutAnyTarget_fallsBackToTheStaticCopy() {
        assertNull(map(L1VerificationStatus.SCANNING, progress(ShadowSyncPhase.CONNECTING)))
        assertNull(map(L1VerificationStatus.SCANNING, ShadowSyncProgress.IDLE))
    }

    @Test
    fun scanning_clampsHeightToTarget_andPercentTo100() {
        assertEquals(
            ShieldedVerificationStatus.Scanning("100", "100", 100),
            map(
                L1VerificationStatus.SCANNING,
                progress(
                    ShadowSyncPhase.FILTERS, percent = 100.9,
                    filterHeight = 105, filterTarget = 100
                )
            )
        )
    }

    @Test
    fun probing_mapsToAlmostDone() {
        assertEquals(
            ShieldedVerificationStatus.AlmostDone,
            map(L1VerificationStatus.PROBING, ShadowSyncProgress.IDLE)
        )
    }

    @Test
    fun failed_mapsToFailed_regardlessOfProgress() {
        assertEquals(
            ShieldedVerificationStatus.Failed,
            map(L1VerificationStatus.FAILED, ShadowSyncProgress.IDLE)
        )
        assertEquals(
            ShieldedVerificationStatus.Failed,
            map(
                L1VerificationStatus.FAILED,
                progress(ShadowSyncPhase.FILTERS, filterHeight = 1, filterTarget = 2)
            )
        )
    }

    @Test
    fun unknownAndVerified_fallBackToTheStaticCopy() {
        assertNull(map(L1VerificationStatus.UNKNOWN, ShadowSyncProgress.IDLE))
        assertNull(map(L1VerificationStatus.VERIFIED, ShadowSyncProgress.IDLE))
    }
}
