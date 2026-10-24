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
package de.schildbach.wallet.ui.username.request

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.PlatformHealth
import de.schildbach.wallet.service.platform.PlatformHealthProbe
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.service.platform.sdk.AssetLockFundingEvidence
import de.schildbach.wallet.service.platform.sdk.ContestedUsernameFees
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.platform.sdk.SdkShieldedUsernameCreation
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameCreationOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameNameStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameSubmitState
import de.schildbach.wallet.livedata.Resource
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.BlockInfo
import org.dashj.platform.dpp.voting.ContenderWithSerializedDocument
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.dpp.voting.ContestedDocumentVotePollWinnerInfo
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import de.schildbach.wallet.ui.dashpay.IdentityCreationStatusHolder
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.username.UsernameType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the payment-source routing in
 * [RequestUserNameViewModel]: a SHIELDED_BALANCE submission goes to
 * [SdkShieldedUsernameCreation] (never `CreateIdentityService`), the
 * DASH_BALANCE / invite / reuse-transaction submissions keep the legacy
 * L1 path untouched, the app-scoped submit state is mirrored into the
 * uiState, and the `enoughBalance` gate honors the shielded source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestUserNameViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val shieldedSubmitState =
        MutableStateFlow<ShieldedUsernameSubmitState>(ShieldedUsernameSubmitState.Idle)

    private val shieldedUsernameCreation = mockk<SdkShieldedUsernameCreation> {
        every { submitState } returns shieldedSubmitState
        every { submit(any(), any()) } returns true
        every { acknowledge() } just Runs
    }

    private val transparentSubmitState =
        MutableStateFlow<ShieldedUsernameSubmitState>(ShieldedUsernameSubmitState.Idle)

    private val transparentUsernameCreation = mockk<SdkTransparentUsernameCreation> {
        every { submitState } returns transparentSubmitState
        coEvery { isCutoverCommitted() } returns false
        // A readable state: the routing's strict read agrees with whatever a test stubs above.
        coEvery { cutoverStateOrUnknown() } coAnswers { (self as SdkTransparentUsernameCreation).isCutoverCommitted() }
        every { acknowledge() } just Runs
    }

    private val walletApplication = mockk<WalletApplication>(relaxed = true)

    private val creationStateFlow = MutableStateFlow<String?>(null)
    private val identityConfig = mockk<BlockchainIdentityConfig> {
        every { observe(BlockchainIdentityConfig.IDENTITY_ID) } returns emptyFlow()
        every { observe(BlockchainIdentityConfig.CREATION_STATE) } returns creationStateFlow
        // Observed by the terminal-failure ("Invite has already been used")
        // dialog-dismiss watcher; no error in these scenarios.
        every { observe(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE) } returns emptyFlow()
        coEvery { get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK) } returns null
        coEvery { get(BlockchainIdentityConfig.USERNAME) } returns null
        coEvery { set(BlockchainIdentityConfig.USERNAME, any()) } just Runs
        coEvery { set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, any()) } just Runs
    }

    private val walletBalanceFlow = MutableStateFlow(org.bitcoinj.core.Coin.ZERO)
    private val walletData = mockk<WalletData> {
        every { observeBalance(any(), any()) } returns walletBalanceFlow
    }

    /**
     * Asset-lock funding PREFLIGHT (the S22 "display balance passed, the
     * real build bounced" gate). Default `null` = no evidence → fail OPEN,
     * so every pre-existing expectation is unchanged; the settling tests
     * override it with concrete evidence.
     */
    private val assetLockFundingEvidence = MutableStateFlow<AssetLockFundingEvidence?>(null)

    /**
     * Set the preflight's CLASSIFIED evidence: the mirror has attributed
     * and finalized the whole wallet down to [eligibleDuffs], with nothing
     * left pending — the shape that proves a shortfall.
     */
    private fun classifiedEvidence(eligibleDuffs: Long) {
        assetLockFundingEvidence.value = AssetLockFundingEvidence(eligibleDuffs, 0L)
    }

    /** Preflight read count — the settling-poll cadence assertions. */
    private var eligibilityReads = 0
    private val assetLockFundingPreflight = mockk<SdkAssetLockFundingPreflight> {
        coEvery { assetLockFundingEvidenceOrNull() } answers {
            eligibilityReads++
            assetLockFundingEvidence.value
        }
    }

    private val shieldedSyncStatusFlow = MutableStateFlow(ShieldedSyncStatus.NOT_READY)
    private val shieldedBalanceFlow = MutableStateFlow(Dash.ZERO)
    private val shieldedBalanceService = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns true
        every { observeShieldedBalance() } returns shieldedBalanceFlow
        every { shieldedSyncStatus } returns shieldedSyncStatusFlow
        // Funding-note anchor gate (Part B): default anchored so existing
        // shielded-path expectations (button reaches Enabled at READY) hold;
        // anchor-specific tests can override this stub.
        coEvery { isFundingNoteAnchoredForDenomination(any()) } returns true
    }

    // Every created ViewModel is tracked so tearDown can cancel its
    // viewModelScope BEFORE resetMain(): the VM's flow collections (submit
    // state, shielded balance) never complete on their own, and a collector
    // that outlives the test crashes on the next Dispatchers.Main touch —
    // poisoning whichever test runs next in the class (order-dependent
    // full-suite flakes).
    private val createdViewModels = mutableListOf<RequestUserNameViewModel>()

    private val platformHealthProbe = mockk<PlatformHealthProbe> {
        coEvery { probe() } returns PlatformHealth.UNKNOWN
    }

    /**
     * Protocol-gated contested fee (MO-1069). Defaults to a null read (SDK
     * down / query failed), which resolves to the LEGACY fee pair — the
     * safe fallback and what testnet/mainnet actually run today (protocol
     * 13 / Drive 4.1.x). Tests pinning the v4.2 branch override this with
     * `coEvery { dashSdkService.currentProtocolVersion() } returns 14`
     * before constructing the ViewModel.
     */
    private val dashSdkService = mockk<DashSdkService> {
        coEvery { currentProtocolVersion() } returns null
    }

    private fun viewModel(
        platformRepo: PlatformRepo = mockk<PlatformRepo>(relaxed = true),
        transparentCreation: SdkTransparentUsernameCreation = transparentUsernameCreation
    ) = RequestUserNameViewModel(
        walletApplication = walletApplication,
        identityConfig = identityConfig,
        walletData = walletData,
        platformRepo = platformRepo,
        usernameRequestDao = mockk<UsernameRequestDao>(relaxed = true),
        analytics = mockk<AnalyticsService>(relaxed = true),
        topUpRepository = mockk<TopUpRepository>(relaxed = true),
        shieldedUsernameCreation = shieldedUsernameCreation,
        transparentUsernameCreation = transparentCreation,
        shieldedBalanceService = shieldedBalanceService,
        platformHealthProbe = platformHealthProbe,
        identityCreationStatus = IdentityCreationStatusHolder(),
        assetLockFundingPreflight = assetLockFundingPreflight,
        dashSdkService = dashSdkService
    ).also { createdViewModels += it }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { vm ->
            val scopeJob = vm.viewModelScope.coroutineContext[Job]
            // ViewModelStore.clear() is the real lifecycle path: it cancels
            // viewModelScope AND runs onCleared() (which cancels the VM's IO
            // worker scope). Then JOIN before resetMain() — cancellation
            // completes asynchronously, and a coroutine parked in
            // withContext(Dispatchers.IO) resumes onto Main afterwards;
            // without the join that resume can land after resetMain() and
            // poison whichever test runs next.
            ViewModelStore().apply { put("vm", vm) }.clear()
            scopeJob?.let { runBlocking { it.join() } }
        }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    /**
     * [runTest] wrapper that cancels every created ViewModel's
     * [viewModelScope] the moment the test body finishes — BEFORE [runTest]
     * drains the scheduler.
     *
     * Why this exists: the VM's settling-eligibility poll is an infinite
     * `while (true) { delay(...); emit(Unit) }` loop collected on
     * `Dispatchers.Main`, which [setup] points at the shared
     * [TestCoroutineScheduler][kotlinx.coroutines.test.TestCoroutineScheduler].
     * Under `runTest` virtual time every `delay` is skipped instantly, so a
     * test that returns while `fundsSettling` is still true leaves a
     * scheduler that NEVER goes idle — `runTest`'s own advance-until-idle
     * spins forever and the whole suite hangs (observed as the full
     * `test_testNet3DebugUnitTest` run parking indefinitely in
     * `checkUsernameValid_dashSource_settlingFunds_gateTheCreation`). The
     * [tearDown] cleanup can't help: JUnit never reaches `@After` because
     * `runTest` never returns. Cancelling the VM scopes inside the test
     * coroutine's `finally` unschedules the poll, the scheduler goes idle,
     * and `runTest` completes; [tearDown] then still runs the full
     * lifecycle clear + join.
     */
    /** Waits (in real time) for [condition]; the routing under test may hop dispatchers. */
    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for the condition" }
            Thread.sleep(10)
        }
    }

    private fun runVmTest(testBody: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            testBody()
        } finally {
            createdViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    // ── reset() vs the shielded-gate mirrors ────────────────────────────────

    @Test
    fun reset_preservesTheShieldedGateMirrors() = runVmTest {
        val viewModel = viewModel()
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
        shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY
        // The collectors have mirrored the service state into uiState.
        assertEquals(ShieldedSyncStatus.READY, viewModel.uiState.value.shieldedSyncStatus)
        assertEquals(true, viewModel.uiState.value.fundingNoteAnchored)

        // reset() runs on EVERY empty username input, including screen entry.
        viewModel.reset()

        // The service will not re-emit an unchanged READY, so a wiped mirror
        // could never repair itself — the button read "Preparing shielded
        // balance…" for the rest of the screen's life (S21 repro 2026-08-18).
        assertEquals(ShieldedSyncStatus.READY, viewModel.uiState.value.shieldedSyncStatus)
        assertEquals(true, viewModel.uiState.value.fundingNoteAnchored)
    }

    // ── the EFFECTIVE payment source (Mo-973 fallback) ──────────────────────

    @Test
    fun effectiveSource_contestedShieldedShortfall_fallsBackToDashBalance() = runVmTest {
        val viewModel = viewModel()
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
        shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY
        // Pool covers the 0.03 non-contested denomination but not 0.25.
        shieldedBalanceFlow.value = Dash(4_787_148L)
        // L1 covers the contested fee (preflight evidence defaults open).
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(94_900_000)

        // Contested: the pool cannot fund 0.25 → the Dash-balance path is the
        // effective source (this is what the fragment's button gate consumes).
        assertEquals(
            UsernamePaymentSource.DASH_BALANCE,
            viewModel.effectivePaymentSourceFor(contestable = true)
        )
        // Non-contested: the pool funds it — the raw source stands.
        assertEquals(
            UsernamePaymentSource.SHIELDED_BALANCE,
            viewModel.effectivePaymentSourceFor(contestable = false)
        )
    }

    // ── paymentSource routing ───────────────────────────────────────────────

    @Test
    fun submit_shieldedSource_routesToTheShieldedService_neverTheL1Service() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

        viewModel.submit()

        // submit() hops over Dispatchers.IO for the config write — poll.
        verify(exactly = 1, timeout = 5_000) { shieldedUsernameCreation.submit("alice2", null) }
        // The shielded path now starts the lightweight CreateIdentityService
        // foreground HOLD (Fix A1) — the same process-foreground + home-tile
        // driver the transparent path uses — once the executor accepts the
        // submit. It carries NO funding (the executor owns the shielded spend).
        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
    }

    @Test
    fun submit_dashSource_keepsTheLegacyL1PathUntouched() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        // DASH_BALANCE is the default paymentSource.

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        verify(exactly = 0) { shieldedUsernameCreation.submit(any(), any()) }
    }

    @Test
    fun l1Creation_terminalState_clearsTheProcessingDialog() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.submit() // L1 path: sets usernameRequestSubmitting = true
        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }

        // CreateIdentityService completes out-of-band; the persisted state flips DONE.
        creationStateFlow.value = IdentityCreationState.DONE.name

        // The dialog gate must clear so the processing dialog dismisses.
        assertEquals(false, viewModel.uiState.value.usernameRequestSubmitting)
        assertEquals(true, viewModel.uiState.value.usernameRequestSubmitted)
    }

    @Test
    fun l1Creation_terminalState_withNoSubmitInFlight_isIgnored() = runVmTest {
        val viewModel = viewModel()
        // No submit() — a pre-existing DONE state (e.g. re-entering the screen)
        // must not fabricate a "submitted" completion.
        creationStateFlow.value = IdentityCreationState.DONE.name

        assertEquals(false, viewModel.uiState.value.usernameRequestSubmitting)
        assertEquals(false, viewModel.uiState.value.usernameRequestSubmitted)
    }

    @Test
    fun submit_reuseTransaction_staysLegacyEvenWithShieldedSource() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
        // A locked/lost-vote identity re-uses its existing asset-lock
        // funding — never the shielded pool.
        viewModel.identity = BlockchainIdentityData(
            IdentityCreationState.VOTING,
            null,
            "alice2",
            null,
            "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
            false,
            usernameRequested = UsernameRequestStatus.LOCKED
        )

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        verify(exactly = 0) { shieldedUsernameCreation.submit(any(), any()) }
    }

    @Test
    fun submit_afterTheCutover_retriesOnlyTheNameOfAnIdentityAlreadyRegistered() = runVmTest {
        // Review 4299080469: the identity exists on chain and only its DPNS name was rejected.
        // CreateIdentityService's reuse path would reset it to NONE and start fresh legacy
        // funding, which post-cutover has no dashj asset lock to find.
        coEvery { transparentUsernameCreation.isCutoverCommitted() } returns true
        every { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) } returns true
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice3"
        viewModel.identity = BlockchainIdentityData(
            IdentityCreationState.USERNAME_REGISTERING,
            "username registration did not complete (retryable): domain document rejected",
            "alice2",
            null,
            "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
            false
        )

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) {
            transparentUsernameCreation.retryNameForExistingIdentity(
                "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
                "alice3",
                null
            )
        }
        verify(exactly = 0) { walletApplication.startService(any()) }
        verify(exactly = 0) { transparentUsernameCreation.submit(any(), any()) }
    }

    @Test
    fun submit_withTheCutoverStateUnknown_neverResetsARegisteredIdentity() = runVmTest {
        // Review 4299080469 (reopened): the lenient read turns an unreadable state into "not
        // committed", which sent a registered SDK identity down the legacy reuse path - reset to
        // NONE, fresh legacy funding. Unknown must refuse, touching nothing.
        coEvery { transparentUsernameCreation.isCutoverCommitted() } returns false
        coEvery { transparentUsernameCreation.cutoverStateOrUnknown() } returns null
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice3"
        val registered = BlockchainIdentityData(
            IdentityCreationState.USERNAME_REGISTERING,
            "username registration did not complete (retryable): domain document rejected",
            "alice2",
            null,
            "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
            false
        )
        viewModel.identity = registered

        viewModel.submit()

        awaitCondition { viewModel.uiState.value.usernameSubmittedError }
        verify(exactly = 0) { walletApplication.startService(any()) }
        verify(exactly = 0) { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) }
        verify(exactly = 0) { transparentUsernameCreation.submit(any(), any()) }
        assertEquals("nothing reset", registered, viewModel.identity)
        assertEquals(false, viewModel.uiState.value.usernameRequestSubmitting)
    }

    @Test
    fun submit_withTheCutoverStateUnknown_neverFundsAFreshRegistrationThroughDashj() = runVmTest {
        coEvery { transparentUsernameCreation.isCutoverCommitted() } returns false
        coEvery { transparentUsernameCreation.cutoverStateOrUnknown() } returns null
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice4"

        viewModel.submit()

        awaitCondition { viewModel.uiState.value.usernameSubmittedError }
        verify(exactly = 0) { walletApplication.startService(any()) }
        verify(exactly = 0) { transparentUsernameCreation.submit(any(), any()) }
    }

    @Test
    fun submit_beforeTheCutover_keepsTheLegacyReusePath() = runVmTest {
        // master's case (#1567): a dashj-created identity reuses its dashj funding transaction
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice3"
        viewModel.identity = BlockchainIdentityData(
            IdentityCreationState.USERNAME_REGISTERING,
            "missing domain document for alice2",
            "alice2",
            null,
            "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
            false
        )

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        verify(exactly = 0) { transparentUsernameCreation.retryNameForExistingIdentity(any(), any(), any()) }
    }

    @Test
    fun submit_nameRetry_handoffSurvivesTheScreenClosing() = runVmTest {
        // Review 5556781925: the submitting flag sends the user Home and finishes this activity,
        // which clears the view model. The name retry must still reach its worker handoff.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handedOff = java.util.concurrent.CopyOnWriteArrayList<String>()
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        try {
            val realCreation = SdkTransparentUsernameCreation(
                source = mockk(relaxed = true),
                cutoverCommitted = { true },
                feeDuffs = { 0L },
                handOffToLegacy = { handedOff += it },
                // the DataStore write is slow: the screen closes while it is in flight
                persistNameContext = { _, _, _, _ -> gate.await() },
                executorScope = appScope
            )
            val viewModel = viewModel(transparentCreation = realCreation)
            viewModel.requestedUserName = "alice3"
            viewModel.identity = BlockchainIdentityData(
                IdentityCreationState.USERNAME_REGISTERING,
                "username registration did not complete (retryable): domain document rejected",
                "alice2",
                null,
                "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
                false
            )

            viewModel.submit()
            awaitReal("the submitting state that navigates home") { viewModel.uiState.value.usernameRequestSubmitting }
            // the activity finishes: its view model is cleared
            viewModel.viewModelScope.cancel()
            gate.complete(Unit)

            awaitReal("the worker handoff") { handedOff.isNotEmpty() }
            assertEquals(listOf("6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"), handedOff.toList())
        } finally {
            appScope.cancel()
        }
    }

    /** Polls on real time: the work under test runs on real dispatchers, not the test scheduler. */
    private suspend fun awaitReal(what: String, condition: () -> Boolean) {
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition()) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
                kotlinx.coroutines.delay(10)
            }
        }
    }

    // ── Submit-state mirroring ──────────────────────────────────────────────

    @Test
    fun shieldedProving_mirrorsAsSubmitting() = runVmTest {
        val viewModel = viewModel()

        shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving

        assertTrue(viewModel.uiState.value.usernameRequestSubmitting)
        assertFalse(viewModel.uiState.value.usernameSubmittedError)
    }

    @Test
    fun shieldedCreated_mirrorsAsSubmitted_andAcknowledges() = runVmTest {
        val viewModel = viewModel()

        shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving
        shieldedSubmitState.value = ShieldedUsernameSubmitState.Created(
            ShieldedUsernameCreationOutcome(
                "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
                ShieldedUsernameNameStatus.REGISTERED
            )
        )

        val state = viewModel.uiState.value
        assertFalse(state.usernameRequestSubmitting)
        assertTrue(state.usernameRequestSubmitted)
        verify(exactly = 1) { shieldedUsernameCreation.acknowledge() }
    }

    @Test
    fun shieldedNotSent_mirrorsAsError_andAcknowledgesForRetry() = runVmTest {
        val viewModel = viewModel()

        shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving
        shieldedSubmitState.value = ShieldedUsernameSubmitState.NotSent("balance too low")

        val state = viewModel.uiState.value
        assertFalse(state.usernameRequestSubmitting)
        assertTrue(state.usernameSubmittedError)
        assertFalse(state.usernameSubmittedPoolSyncing)
        verify(exactly = 1) { shieldedUsernameCreation.acknowledge() }
    }

    @Test
    fun shieldedNotSent_poolStillSyncing_surfacesCalmSyncingState_notTheRedError() =
        runVmTest {
            // The live incident: the pool dropped back to SYNCING after a
            // service teardown and the submit reached the SDK, which refused
            // with "shielded pool still syncing". That must surface the calm
            // "still preparing" state — NOT the red network-error dialog.
            val viewModel = viewModel()

            shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving
            shieldedSubmitState.value = ShieldedUsernameSubmitState.NotSent(
                SdkShieldedUsernameCreation.REASON_POOL_STILL_SYNCING
            )

            val state = viewModel.uiState.value
            assertFalse(state.usernameRequestSubmitting)
            assertTrue(state.usernameSubmittedPoolSyncing)
            assertFalse(state.usernameSubmittedError)
            // Retry-safe (nothing spent) — acknowledged so a later submit works.
            verify(exactly = 1) { shieldedUsernameCreation.acknowledge() }
        }

    @Test
    fun transparentNotSent_paymentStillConfirming_surfacesThatMessage_notTheRedError() = runVmTest {
        // Review 1b15e950af15: a fresh registration is refused while an uncertain payment's
        // inputs are exposed to coin selection. Nothing was spent; the screen says why.
        val viewModel = viewModel()

        transparentSubmitState.value = ShieldedUsernameSubmitState.Proving
        transparentSubmitState.value = ShieldedUsernameSubmitState.NotSent(
            "pending payment inputs unprotected"
        )

        val state = viewModel.uiState.value
        assertFalse(state.usernameRequestSubmitting)
        assertTrue(state.usernameSubmittedPaymentStillConfirming)
        assertFalse(state.usernameSubmittedError)
        assertFalse(state.usernameSubmittedPoolSyncing)
        verify(exactly = 1) { transparentUsernameCreation.acknowledge() }
    }

    @Test
    fun shieldedNotSent_runtimeNotReady_alsoSurfacesTheCalmSyncingState() = runVmTest {
        val viewModel = viewModel()

        shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving
        shieldedSubmitState.value = ShieldedUsernameSubmitState.NotSent(
            SdkShieldedUsernameCreation.REASON_RUNTIME_NOT_READY
        )

        val state = viewModel.uiState.value
        assertTrue(state.usernameSubmittedPoolSyncing)
        assertFalse(state.usernameSubmittedError)
    }

    @Test
    fun shieldedAmbiguous_mirrorsAsAmbiguous_neverTheRetryableError() = runVmTest {
        val viewModel = viewModel()

        shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving
        shieldedSubmitState.value = ShieldedUsernameSubmitState.MayHaveGoneThrough

        val state = viewModel.uiState.value
        assertFalse(state.usernameRequestSubmitting)
        // NOT the generic error: that dialog offers "Try again at no extra
        // cost", both wrong for an unconfirmed (possibly on-chain) outcome.
        assertFalse(state.usernameSubmittedError)
        assertTrue(state.usernameSubmittedAmbiguous)
        // Never acknowledged: the sticky state keeps refusing re-submission.
        verify(exactly = 0) { shieldedUsernameCreation.acknowledge() }
    }

    // ── enoughBalance reconciliation ────────────────────────────────────────

    @Test
    fun checkUsernameValid_shieldedSource_nonContestedName_passesWithZeroL1Balance() =
        runVmTest {
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            // "alice2" contains a 2-9 digit → non-contested.
            viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

            assertTrue(viewModel.uiState.value.enoughBalance)
        }

    @Test
    fun checkUsernameValid_dashSource_nonContestedName_staysL1Gated() = runVmTest {
        val viewModel = viewModel()
        // Default DASH_BALANCE; the L1 balance is zero.

        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

        assertFalse(viewModel.uiState.value.enoughBalance)
    }

    // ── asset-lock funding preflight (the S22 settling-funds gate) ──────────

    @Test
    fun checkUsernameValid_dashSource_settlingFunds_gateTheCreation() = runVmTest {
        // The S22 repro: display balance ~0.994 DASH covers the 0.03 fee,
        // but the funds the asset-lock build can actually select (final
        // BIP44 coins) are 1449 duffs of dust — the old gate let the user
        // pick a name and sit through the ~30s dialog before the build
        // bounced "Insufficient funds: available 1449, required 3000000".
        classifiedEvidence(1_449L)
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_000L)
        val viewModel = viewModel()

        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

        val state = viewModel.uiState.value
        assertFalse(state.enoughBalance)
        assertTrue(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_freshlyFundedWallet_passes_nonContested() = runVmTest {
        // S21 mainnet, 11.10.67: a wallet holding one 0.09401442 DASH
        // receive was refused "You need at least 0.03 spendable Dash to
        // create a username" for the nine minutes between the engine
        // IS-locking the receive and the block landing — the SDK mirror
        // writes neither the account attribution nor any finality signal
        // before the block, so the preflight's eligible sum was 0 and the
        // gate read that 0 as a proven shortfall. Unclassified value that
        // could cover the fee means the mirror is behind the engine, not
        // that the wallet is short.
        assetLockFundingEvidence.value = AssetLockFundingEvidence(
            eligibleDuffs = 0L,
            unclassifiedDuffs = 9_401_442L
        )
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(9_401_442L)
        val viewModel = viewModel()

        viewModel.checkUsernameValid("brian-s21", UsernameType.Primary)

        val state = viewModel.uiState.value
        assertFalse("brian-s21 must be non-contested", state.usernameContestable)
        assertEquals("0.03", state.requiredAmount)
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun refreshContestedFees_afterUnresolvedFirstRead_picksUpProtocol14() = runVmTest {
        // init() ran against a null (unreachable) read → legacy quote. The flow entry
        // refresh must re-read and move the quote to CURRENT once protocol 14 is
        // reported, instead of pinning the legacy requirement for the ViewModel's
        // whole lifetime (MO-1069 re-review finding).
        coEvery { dashSdkService.currentProtocolVersion() } returns null
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(ContestedUsernameFees.LEGACY, vm.contestedFees.value)

        coEvery { dashSdkService.currentProtocolVersion() } returns 14
        vm.refreshContestedFees()
        advanceUntilIdle()
        assertEquals(ContestedUsernameFees.CURRENT, vm.contestedFees.value)
    }

    @Test
    fun checkUsernameValid_dashSource_freshlyFundedWallet_passes_contested_legacyFee() = runVmTest {
        // The same pre-block mirror state at the legacy 0.25 DASH contested
        // fee (protocol 13 / Drive 4.1.x, what testnet/mainnet run today):
        // a wallet comfortably above the threshold must not be refused
        // either (the S21 log failed both, at 0.03 and at the contested fee).
        coEvery { dashSdkService.currentProtocolVersion() } returns null
        assetLockFundingEvidence.value = AssetLockFundingEvidence(
            eligibleDuffs = 0L,
            unclassifiedDuffs = 50_000_000L
        )
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(50_000_000L)
        val viewModel = viewModel()

        viewModel.checkUsernameValid("brian", UsernameType.Primary)

        val state = viewModel.uiState.value
        assertTrue("brian must be contested", state.usernameContestable)
        assertEquals("0.25", state.requiredAmount)
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_freshlyFundedWallet_passes_contested_currentFee() = runVmTest {
        // Same scenario once a live protocol-version read confirms Platform
        // v4.2 (protocol >= 14) is active: the contested fee drops to 0.15.
        coEvery { dashSdkService.currentProtocolVersion() } returns 14
        assetLockFundingEvidence.value = AssetLockFundingEvidence(
            eligibleDuffs = 0L,
            unclassifiedDuffs = 50_000_000L
        )
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(50_000_000L)
        val viewModel = viewModel()

        viewModel.checkUsernameValid("brian", UsernameType.Primary)

        val state = viewModel.uiState.value
        assertTrue("brian must be contested", state.usernameContestable)
        assertEquals("0.15", state.requiredAmount)
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_contestedBalanceGate_requiresTheLegacyAmount() = runVmTest {
        // MO-1069 regression: a wallet funded with exactly the CURRENT
        // (v4.2) 0.15 DASH contested fee must NOT pass the gate while the
        // live protocol version is still 13 (unresolved/null reads the same
        // way) — the network still requires the legacy 0.25, and the SDK
        // rejects a 0.15-funded identity's DPNS registration with
        // "Insufficient identity balance … required 20000100000".
        coEvery { dashSdkService.currentProtocolVersion() } returns null
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(15_000_000L) // 0.15 DASH
        val viewModel = viewModel()

        viewModel.checkUsernameValid("brian", UsernameType.Primary)

        val state = viewModel.uiState.value
        assertTrue("brian must be contested", state.usernameContestable)
        assertEquals("0.25", state.requiredAmount)
        assertFalse("0.15 DASH must not satisfy the legacy 0.25 requirement", state.enoughBalance)
    }

    @Test
    fun checkUsernameValid_dashSource_eligibleFunds_pass() = runVmTest {
        // Eligible sum covers fee + headroom → the gate opens normally.
        classifiedEvidence(99_400_000L)
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_000L)
        val viewModel = viewModel()

        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

        val state = viewModel.uiState.value
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_noPreflightEvidence_failsOpen() = runVmTest {
        // Preflight has no evidence (pre-cutover / SDK unavailable) — the
        // display-balance gate alone decides; the flow must never be
        // blocked on an unrelated hiccup.
        assetLockFundingEvidence.value = null
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_000L)
        val viewModel = viewModel()

        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

        val state = viewModel.uiState.value
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_gateRecomputes_whenFundsSettle() = runVmTest {
        // Settling funds become final (IS-lock/confirmation lands): the
        // balance emission re-reads the eligibility and the gate must
        // self-correct without retyping — the same async-input recompute
        // contract the shielded status gate has.
        classifiedEvidence(1_449L)
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_000L)
        val viewModel = viewModel()
        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)
        assertTrue(viewModel.uiState.value.fundsSettling)

        classifiedEvidence(99_400_000L)
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_001L)

        val state = viewModel.uiState.value
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_settlingGate_clearsByPoll_withoutABalanceEmission() = runVmTest {
        // The S21 staleness repro (build 11.10.46): the settling row was
        // showing, the change output then CONFIRMED on the SDK mirror at
        // 10:38:41 — but confirming an already-counted output changes no
        // balance AMOUNT, so observeBalance never re-emitted and the gate
        // stayed stale until the user left and re-entered the screen. The
        // settling-state poll must re-read the preflight and clear the gate
        // in place.
        classifiedEvidence(1_449L)
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_000L)
        val viewModel = viewModel()
        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)
        assertTrue(viewModel.uiState.value.fundsSettling)

        // Finality flips on the SDK mirror; NO walletBalanceFlow emission.
        classifiedEvidence(99_400_000L)
        dispatcher.scheduler.advanceTimeBy(RequestUserNameViewModel.SETTLING_ELIGIBILITY_POLL_MS + 1)
        dispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.enoughBalance)
        assertFalse(state.fundsSettling)
    }

    @Test
    fun checkUsernameValid_dashSource_settlingPoll_stopsOnceTheGateClears() = runVmTest {
        // The poll exists only while the settling row shows — once the gate
        // resolves, ticks must stop (no idle background DB reads).
        classifiedEvidence(1_449L)
        walletBalanceFlow.value = org.bitcoinj.core.Coin.valueOf(99_400_000L)
        val viewModel = viewModel()
        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)
        assertTrue(viewModel.uiState.value.fundsSettling)

        classifiedEvidence(99_400_000L)
        dispatcher.scheduler.advanceTimeBy(RequestUserNameViewModel.SETTLING_ELIGIBILITY_POLL_MS + 1)
        dispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.fundsSettling)

        val readsAfterClear = eligibilityReads
        dispatcher.scheduler.advanceTimeBy(10 * RequestUserNameViewModel.SETTLING_ELIGIBILITY_POLL_MS)
        dispatcher.scheduler.runCurrent()
        assertEquals(readsAfterClear, eligibilityReads)
    }

    @Test
    fun checkUsernameValid_shieldedSource_contestedName_gatedOnTheContestedDenomination() =
        runVmTest {
            // Contested via shielded needs the 0.3 exit denomination in the
            // pool (0.25 contested fee → smallest covering denomination); a
            // pool that could fund a non-contested name (0.1) must not
            // unlock a contested one.
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY
            shieldedBalanceFlow.value = Dash(10_000_000L) // 0.1 DASH
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            viewModel.checkUsernameValid("alice", de.schildbach.wallet.ui.username.UsernameType.Primary)

            val state = viewModel.uiState.value
            assertTrue(state.usernameContestable)
            assertFalse(state.enoughBalance)
        }

    @Test
    fun checkUsernameValid_shieldedSource_contestedName_passesWhenThePoolCoversTheDenomination() =
        runVmTest {
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY
            shieldedBalanceFlow.value = Dash(30_000_000L) // 0.3 DASH
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            viewModel.checkUsernameValid("alice", de.schildbach.wallet.ui.username.UsernameType.Primary)

            val state = viewModel.uiState.value
            assertTrue(state.usernameContestable)
            assertTrue(state.enoughBalance)
        }

    @Test
    fun checkUsernameValid_shieldedSource_contestedName_midSyncBalanceNeverUnlocks() =
        runVmTest {
            // A mid-sync balance is a placeholder, not evidence — even a
            // covering amount must not unlock while the pool isn't READY.
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.NOT_READY
            shieldedBalanceFlow.value = Dash(30_000_000L)
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            viewModel.checkUsernameValid("alice", de.schildbach.wallet.ui.username.UsernameType.Primary)

            assertFalse(viewModel.uiState.value.enoughBalance)
        }

    @Test
    fun checkUsernameValid_shieldedSource_contestedName_gateRecomputesWhenTheSyncCompletes() =
        runVmTest {
            // The live silent-swallow: the user typed the contested name
            // while the pool was still syncing (gate false), the sync
            // reached READY seconds later — and nothing ever recomputed
            // the gate, leaving the submit button disabled with no
            // explanation. The gate must self-correct from the shielded
            // status/balance flows without retyping.
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.NOT_READY
            shieldedBalanceFlow.value = Dash.ZERO
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
            viewModel.checkUsernameValid("alice", de.schildbach.wallet.ui.username.UsernameType.Primary)
            assertFalse(viewModel.uiState.value.enoughBalance)

            shieldedBalanceFlow.value = Dash(30_000_000L) // 0.3 DASH
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY

            assertTrue(viewModel.uiState.value.enoughBalance)
        }

    @Test
    fun checkUsernameValid_shieldedSource_contestedName_gateFailureNamesTheDenomination() =
        runVmTest {
            // The insufficient-balance row must name the 0.25 funding
            // denomination the contested creation actually needs (the smallest
            // member of the allowed exit-denomination set that covers the 0.25
            // contested fee), not a hardcoded L1 amount.
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY
            shieldedBalanceFlow.value = Dash(10_000_000L) // 0.1 DASH — not enough
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            viewModel.checkUsernameValid("alice", de.schildbach.wallet.ui.username.UsernameType.Primary)

            val state = viewModel.uiState.value
            assertFalse(state.enoughBalance)
            assertEquals(Dash(25_000_000L).toPlainString(), state.requiredAmount)
        }

    // ── Never-silent submits ────────────────────────────────────────────────

    @Test
    fun submit_shieldedRefused_surfacesTheErrorState_neverSilent() = runVmTest {
        // The executor refusing a submit (no scope / busy) used to be
        // swallowed: no dialog, no state change — the user saw nothing.
        every { shieldedUsernameCreation.submit(any(), any()) } returns false
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { shieldedUsernameCreation.submit("alice2", null) }
        // The error state lands on the IO continuation right after the
        // refused call — await it instead of racing it.
        viewModel.uiState.first { it.usernameSubmittedError }
        assertTrue(viewModel.uiState.value.usernameSubmittedError)
    }

    @Test
    fun submit_shieldedRefusedWhileAmbiguous_surfacesAmbiguous_neverTheRetryableError() =
        runVmTest {
            // A refusal caused by the sticky may-have-gone-through state
            // must re-surface the funds-safety dialog, not the "try again
            // at no extra cost" error.
            every { shieldedUsernameCreation.submit(any(), any()) } returns false
            shieldedSubmitState.value = ShieldedUsernameSubmitState.MayHaveGoneThrough
            val viewModel = viewModel()
            viewModel.requestedUserName = "alice2"
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            viewModel.submit()

            verify(exactly = 1, timeout = 5_000) { shieldedUsernameCreation.submit("alice2", null) }
            viewModel.uiState.first { it.usernameSubmittedAmbiguous }
            val state = viewModel.uiState.value
            assertTrue(state.usernameSubmittedAmbiguous)
            assertFalse(state.usernameSubmittedError)
        }

    // ── Fail-closed availability check ──────────────────────────────────────

    @Test
    fun checkUsername_lookupFailure_failsClosed_neverReadsAsAvailable() = runVmTest {
        // The live bug: a failed getUsername (network error, DAPI timeout)
        // defaulted to usernameExists=false with usernameCheckSuccess=true —
        // an already-registered name showed as available on-device.
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("brian-s21-demo") } returns Resource.error("DAPI timeout", null)
        }
        val viewModel = viewModel(platformRepo)

        viewModel.checkUsername("brian-s21-demo")

        // checkUsername hops over Dispatchers.IO — await the failed state.
        viewModel.uiState.first { it.usernameCheckFailed }
        val state = viewModel.uiState.value
        assertFalse(state.usernameCheckSuccess)
        assertFalse(state.checkingUsername)
        // Failed before the contest lookup: nothing else queried.
        verify(exactly = 0) { platformRepo.getVoteContendersOrThrow(any()) }
    }

    @Test
    fun checkUsername_contendersFailure_failsClosed_neverReadsAsNotContested() = runVmTest {
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } returns Resource.success(null)
            every { getVoteContendersOrThrow("alice") } throws IllegalStateException("DPNS read failed")
        }
        val viewModel = viewModel(platformRepo)

        viewModel.checkUsername("alice")

        viewModel.uiState.first { it.usernameCheckFailed }
        val state = viewModel.uiState.value
        assertFalse(state.usernameCheckSuccess)
        assertFalse(state.checkingUsername)
    }

    @Test
    fun checkUsername_retriggerAfterFailure_clearsTheFailedStateAndCompletes() = runVmTest {
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } returns
                Resource.error("DAPI timeout", null) andThen Resource.success(null)
            every { getVoteContendersOrThrow("alice") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        viewModel.checkUsername("alice")
        viewModel.uiState.first { it.usernameCheckFailed }

        // The debounced re-check (retyping / retry) clears the failure and
        // completes normally: available, uncontested, unblocked.
        viewModel.checkUsername("alice")
        viewModel.uiState.first { it.usernameCheckSuccess }
        val state = viewModel.uiState.value
        assertFalse(state.usernameCheckFailed)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameContested)
        assertFalse(state.usernameBlocked)
    }

    // ── Stale availability results (CodeRabbit, #1564) ──────────────────────

    @Test
    fun checkUsername_lateResultForAReplacedName_doesNotOverwriteTheCurrentVerdict() = runVmTest {
        // Two DAPI lookups can be in flight at once: the 600 ms debounce only drops a
        // runnable that has not fired, so once a query is away more typing does not
        // touch it. Here 'alice' (free) is held open until 'bob' (taken) has answered,
        // then released — the classic out-of-order completion.
        val aliceInFlight = CountDownLatch(1)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } answers {
                aliceInFlight.await()
                Resource.success(null)
            }
            every { getVoteContendersOrThrow("alice") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
            every { getUsername("bob") } returns Resource.success(mockk<Document>())
            every { getVoteContendersOrThrow("bob") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        viewModel.checkUsernameValid("alice", UsernameType.Primary)
        val aliceLookup = viewModel.checkUsername("alice")

        // The user types on: the field now holds 'bob', whose lookup answers first.
        try {
            viewModel.checkUsernameValid("bob", UsernameType.Primary)
            viewModel.checkUsername("bob")?.join()
            assertTrue("bob is registered", viewModel.uiState.value.usernameExists)
        } finally {
            aliceInFlight.countDown()
        }
        aliceLookup?.join()

        val state = viewModel.uiState.value
        assertTrue("bob's verdict must survive the late arrival", state.usernameExists)
        assertFalse("and alice's 'free' must not have been recorded", state.usernameCheckFailed)
    }

    @Test
    fun checkUsername_lateResultAfterTheFieldIsCleared_isDropped() = runVmTest {
        val inFlight = CountDownLatch(1)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } answers {
                inFlight.await()
                Resource.success(null)
            }
            every { getVoteContendersOrThrow("alice") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        viewModel.checkUsernameValid("alice", UsernameType.Primary)
        val lookup = viewModel.checkUsername("alice")

        // Field cleared while the query is out.
        viewModel.reset()

        inFlight.countDown()
        lookup?.join()

        assertFalse(
            "a cleared field has no current name for a verdict to describe",
            viewModel.uiState.value.usernameCheckSuccess
        )
    }

    @Test
    fun checkUsername_lateFailureForAReplacedName_doesNotRaiseTheFailedFlag() = runVmTest {
        // The fail-closed paths need the same gate: a stale failure would otherwise
        // show the retry banner for a name the user has already moved past.
        val aliceInFlight = CountDownLatch(1)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } answers {
                aliceInFlight.await()
                Resource.error("DAPI timeout", null)
            }
            every { getUsername("bob") } returns Resource.success(null)
            every { getVoteContendersOrThrow("bob") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        viewModel.checkUsernameValid("alice", UsernameType.Primary)
        val aliceLookup = viewModel.checkUsername("alice")

        viewModel.checkUsernameValid("bob", UsernameType.Primary)
        viewModel.checkUsername("bob")?.join()

        aliceInFlight.countDown()
        aliceLookup?.join()

        val state = viewModel.uiState.value
        assertFalse("alice's failure must not surface under bob", state.usernameCheckFailed)
        assertTrue("bob's success must stand", state.usernameCheckSuccess)
    }

    @Test
    fun checkUsername_sameLabelRequestedTwice_dropsTheFirstLookup() = runVmTest {
        // CodeRabbit follow-up on #1564: alice -> bob -> alice. Both the first and the
        // third request carry the label "alice", so matching on the LABEL would let the
        // first one's answer through while the third is still in flight.
        //
        // The two alice lookups must DISAGREE for this to prove anything — otherwise the
        // stale write is indistinguishable from the fresh one. The first sees the name as
        // taken (a read from before it was released, say); the second sees it free. Only
        // the second is current, so the field must end up reading "available".
        val firstAlice = CountDownLatch(1)
        // getUsername runs on a real Dispatchers.IO thread, so starting a lookup does not
        // establish that it has ENTERED the mock. Without this signal the replacement
        // alice could arrive first, take the blocked branch, and the test would join it
        // before the finally releases the latch — a timeout instead of a failure.
        val firstAliceEntered = CountDownLatch(1)
        var aliceCalls = 0
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } answers {
                if (aliceCalls++ == 0) {
                    firstAliceEntered.countDown()
                    firstAlice.await()
                    Resource.success(mockk<Document>())   // stale: "taken"
                } else {
                    Resource.success(null)                // current: "free"
                }
            }
            every { getVoteContendersOrThrow("alice") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
            every { getUsername("bob") } returns Resource.success(null)
            every { getVoteContendersOrThrow("bob") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        val staleAlice = try {
            viewModel.checkUsernameValid("alice", UsernameType.Primary)
            val job = viewModel.checkUsername("alice")
            // Only now is the blocked response deterministically bound to the OLDER token.
            assertTrue(
                "the first alice lookup reached the mock",
                firstAliceEntered.await(10, TimeUnit.SECONDS)
            )

            viewModel.checkUsernameValid("bob", UsernameType.Primary)
            viewModel.checkUsername("bob")?.join()

            // Back to alice — a NEW lookup, which answers immediately and says "free".
            viewModel.checkUsernameValid("alice", UsernameType.Primary)
            viewModel.checkUsername("alice")?.join()
            assertTrue("the second alice lookup landed", viewModel.uiState.value.usernameCheckSuccess)
            assertFalse("and reported alice available", viewModel.uiState.value.usernameExists)
            job
        } finally {
            firstAlice.countDown()
        }

        // The first alice lookup now completes, saying "taken". Same label, older token.
        staleAlice?.join()

        val state = viewModel.uiState.value
        assertFalse(
            "the stale 'taken' verdict must not overwrite the current 'available' one",
            state.usernameExists
        )
        assertTrue(state.usernameCheckSuccess)
        assertFalse(state.checkingUsername)
    }

    @Test
    fun checkUsername_invalidKeystrokeWhileALookupIsOut_doesNotStrandTheSpinner() = runVmTest {
        // Review finding on #1564. A valid name starts a lookup (checkingUsername = true).
        // The user then types something INVALID, so the fragment schedules no replacement
        // query; the outstanding lookup returns through the stale guard, which writes
        // nothing. Without clearing the flag in the validity pass, the availability
        // spinner would run for the rest of the screen's life.
        val inFlight = CountDownLatch(1)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } answers {
                inFlight.await()
                Resource.success(null)
            }
            every { getVoteContendersOrThrow("alice") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        // The latch is released in a finally: a failed assertion must not strand a
        // blocked IO thread, which keeps the test JVM alive instead of failing.
        val lookup = try {
            assertTrue(viewModel.checkUsernameValid("alice", UsernameType.Primary))
            val job = viewModel.checkUsername("alice")
            assertTrue("the lookup is out", viewModel.uiState.value.checkingUsername)

            // '_' is outside [a-zA-Z0-9-], so no replacement lookup is scheduled.
            assertFalse(viewModel.checkUsernameValid("alice_x", UsernameType.Primary))
            assertFalse(
                "the spinner must stop when nothing will answer for the new input",
                viewModel.uiState.value.checkingUsername
            )
            job
        } finally {
            inFlight.countDown()
        }

        // The old lookup completing must not resurrect it either.
        lookup?.join()
        assertFalse(viewModel.uiState.value.checkingUsername)
    }

    @Test
    fun checkUsernameValid_stillValid_leavesTheSpinnerRunningForTheReplacementLookup() = runVmTest {
        // The complement: when a replacement query IS coming, the spinner keeps running
        // across the 600 ms debounce rather than flickering off and on.
        val inFlight = CountDownLatch(1)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername(any()) } answers {
                inFlight.await()
                Resource.success(null)
            }
            every { getVoteContendersOrThrow(any()) } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
                // no finished vote: the check reads the winner before the in-progress rule
                every { winner } returns Optional.empty()
            }
        }
        val viewModel = viewModel(platformRepo)

        val lookup = try {
            viewModel.checkUsernameValid("alice", UsernameType.Primary)
            val job = viewModel.checkUsername("alice")
            assertTrue(viewModel.uiState.value.checkingUsername)

            assertTrue(viewModel.checkUsernameValid("alicia", UsernameType.Primary))
            assertTrue(
                "a replacement lookup is coming — keep the spinner",
                viewModel.uiState.value.checkingUsername
            )
            job
        } finally {
            inFlight.countDown()
        }
        lookup?.join()
    }

    // ── Advisory network-health warning ─────────────────────────────────────

    @Test
    fun checkNetworkHealth_degraded_setsNetworkSlow_neverGatesTheButtonInputs() = runVmTest {
        coEvery { platformHealthProbe.probe() } returns PlatformHealth.DEGRADED
        val viewModel = viewModel()

        viewModel.checkNetworkHealth()

        viewModel.uiState.first { it.networkSlow }
        val state = viewModel.uiState.value
        assertTrue(state.networkSlow)
        // Advisory only: none of the gate inputs may be touched by the probe.
        assertFalse(state.usernameCheckFailed)
        assertFalse(state.usernameSubmittedError)
    }

    @Test
    fun checkNetworkHealth_unknown_showsNoWarning() = runVmTest {
        coEvery { platformHealthProbe.probe() } returns PlatformHealth.UNKNOWN
        val viewModel = viewModel()

        viewModel.checkNetworkHealth()

        assertFalse(viewModel.uiState.value.networkSlow)
    }

    @Test
    fun reset_preservesTheNetworkSlowAdvisory() = runVmTest {
        // Clearing the input resets the per-username state — the
        // screen-entry-scoped health advisory must survive it.
        coEvery { platformHealthProbe.probe() } returns PlatformHealth.DEGRADED
        val viewModel = viewModel()
        viewModel.checkNetworkHealth()
        viewModel.uiState.first { it.networkSlow }

        viewModel.reset()

        assertTrue(viewModel.uiState.value.networkSlow)
    }

    @Test
    fun submit_dashSource_showsAProcessingStatus() = runVmTest {
        // The L1/asset-lock path hands off to CreateIdentityService with a
        // feedback gap until the identity state machine flips — the submit
        // must still show a processing status immediately.
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        assertTrue(viewModel.uiState.value.usernameRequestSubmitting)
    }

    // ── Pure request-button gate (Fix B) ────────────────────────────────────

    @Test
    fun buttonState_shieldedPathSyncing_isPreparingShielded() {
        assertEquals(
            UsernameSubmitButtonState.PreparingShielded,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.SYNCING,
                enoughBalance = true,
                usernameExists = false,
                usernameContestable = true
            )
        )
    }

    @Test
    fun buttonState_shieldedPathNotReady_isPreparingShielded() {
        assertEquals(
            UsernameSubmitButtonState.PreparingShielded,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.NOT_READY,
                enoughBalance = true,
                usernameExists = false,
                usernameContestable = false
            )
        )
    }

    @Test
    fun buttonState_shieldedPathReadyWithEnoughBalance_isEnabled() {
        assertEquals(
            UsernameSubmitButtonState.Enabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.READY,
                enoughBalance = true,
                usernameExists = false,
                usernameContestable = true
            )
        )
    }

    @Test
    fun buttonState_shieldedPathReadyButShortBalance_isDisabled() {
        assertEquals(
            UsernameSubmitButtonState.Disabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.READY,
                enoughBalance = false,
                usernameExists = false,
                usernameContestable = true
            )
        )
    }

    @Test
    fun buttonState_l1PathUnaffectedBySyncStatus() {
        // The L1/Dash-balance path must never be gated on the shielded
        // status: a syncing pool is irrelevant when paying from L1.
        assertEquals(
            UsernameSubmitButtonState.Enabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.DASH_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.SYNCING,
                enoughBalance = true,
                usernameExists = false,
                usernameContestable = false
            )
        )
        assertEquals(
            UsernameSubmitButtonState.Disabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.DASH_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.SYNCING,
                enoughBalance = false,
                usernameExists = false,
                usernameContestable = false
            )
        )
    }

    @Test
    fun buttonState_secondaryPathIgnoresShieldedSyncStatus() {
        // Secondary (instant) usernames are funded from the already-created
        // identity — the shielded status never gates them.
        assertEquals(
            UsernameSubmitButtonState.Enabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Secondary,
                paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.SYNCING,
                enoughBalance = false,
                usernameExists = false,
                usernameContestable = false
            )
        )
        assertEquals(
            UsernameSubmitButtonState.Disabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Secondary,
                paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.READY,
                enoughBalance = true,
                usernameExists = true,
                usernameContestable = false
            )
        )
    }

    @Test
    fun buttonState_existingName_isDisabledEvenWhenReadyAndFunded() {
        assertEquals(
            UsernameSubmitButtonState.Disabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.DASH_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.READY,
                enoughBalance = true,
                usernameExists = true,
                usernameContestable = false
            )
        )
    }

    // ── Finished votes and the current-result guard (master #1567) ───────────
    //
    // A contested name whose vote has ENDED reports an empty contender map and no
    // lock votes, so the in-progress rule alone read a locked or already-won name
    // as available.

    @Test
    fun checkUsername_noDocumentAndNoVote_isAvailable() = runVmTest {
        val state = checkUsernameWith(document = null, contenders = contenders())

        assertTrue(state.usernameCheckSuccess)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameContested)
        assertFalse(state.usernameBlocked)
        assertEquals(CHECKED_NAME, state.checkedUsername)
    }

    @Test
    fun checkUsername_documentExists_isTaken() = runVmTest {
        val state = checkUsernameWith(document = mockk<Document>(), contenders = contenders())

        assertTrue(state.usernameCheckSuccess)
        assertTrue(state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_voteInProgress_isContestedButNotBlocked() = runVmTest {
        val state = checkUsernameWith(
            document = null,
            contenders = contenders(map = mapOf(OTHER_IDENTITY to contender(votes = 3)), lockVoteTally = 1)
        )

        assertTrue(state.usernameCheckSuccess)
        assertTrue(state.usernameContested)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_voteInProgressWithNoContendersAndLockVotes_isBlocked() = runVmTest {
        val state = checkUsernameWith(document = null, contenders = contenders(lockVoteTally = 2))

        assertTrue(state.usernameBlocked)
    }

    @Test
    fun checkUsername_lockedBallotWithEmptyContenderMap_isBlocked() = runVmTest {
        val state = checkUsernameWith(document = null, contenders = contenders(winner = finishedVote(isLocked = true)))

        assertTrue(state.usernameCheckSuccess)
        assertTrue("masternodes locked this name, nobody can register it", state.usernameBlocked)
        assertFalse(state.usernameExists)
    }

    @Test
    fun checkUsername_wonBallotBeforeDomainLookupCatchesUp_isTaken() = runVmTest {
        val state = checkUsernameWith(
            document = null,
            contenders = contenders(winner = finishedVote(isLocked = false, noWinner = false))
        )

        assertTrue(state.usernameCheckSuccess)
        assertTrue("a won name is taken even before its document shows up", state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_ballotEndedWithNoWinner_isAvailableAgain() = runVmTest {
        val state = checkUsernameWith(
            document = null,
            contenders = contenders(winner = finishedVote(isLocked = false, noWinner = true))
        )

        assertTrue(state.usernameCheckSuccess)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameBlocked)
        assertFalse(state.usernameContested)
    }

    @Test
    fun hasAvailableResultFor_onlyMatchesTheCheckedAvailableName() = runVmTest {
        val viewModel = viewModelCheckingWith(document = null, contenders = contenders())
        viewModel.checkUsername(CHECKED_NAME)
        viewModel.uiState.first { it.usernameCheckSuccess }

        assertTrue(viewModel.hasAvailableResultFor(CHECKED_NAME))
        assertFalse(viewModel.hasAvailableResultFor("someothername"))
    }

    @Test
    fun hasAvailableResultFor_isFalseOnceTheInputMovesOn() = runVmTest {
        val viewModel = viewModelCheckingWith(document = null, contenders = contenders())
        viewModel.checkUsername(CHECKED_NAME)
        viewModel.uiState.first { it.usernameCheckSuccess }

        // the user keeps typing: the per-keystroke validity pass clears the result
        viewModel.checkUsernameValid("secondname", UsernameType.Primary)

        assertNull(viewModel.uiState.value.checkedUsername)
        assertFalse(viewModel.hasAvailableResultFor(CHECKED_NAME))
        assertFalse(viewModel.hasAvailableResultFor("secondname"))
    }

    @Test
    fun hasAvailableResultFor_isFalseForALockedName() = runVmTest {
        val viewModel = viewModelCheckingWith(
            document = null,
            contenders = contenders(winner = finishedVote(isLocked = true))
        )
        viewModel.checkUsername(CHECKED_NAME)
        viewModel.uiState.first { it.usernameCheckSuccess }

        assertFalse(viewModel.hasAvailableResultFor(CHECKED_NAME))
    }

    @Test
    fun buttonState_blockedName_isDisabledEvenWhenFunded() {
        assertEquals(
            UsernameSubmitButtonState.Disabled,
            usernameSubmitButtonState(
                usernameType = UsernameType.Primary,
                paymentSource = UsernamePaymentSource.DASH_BALANCE,
                shieldedSyncStatus = ShieldedSyncStatus.READY,
                enoughBalance = true,
                usernameExists = false,
                usernameContestable = true,
                usernameBlocked = true
            )
        )
    }

    // ── USERNAME_REQUESTED recovery (master #1567) ──────────────────────────

    @Test
    fun usernameRequestedAbsent_isNeitherLockedNorLost() = runVmTest {
        // identity creation stuck before the request got a status: the key was never written
        givenRequestedName(IdentityCreationState.USERNAME_REGISTERING, requested = null)
        val viewModel = viewModel()

        assertFalse(viewModel.isUsernameLocked())
        assertFalse(viewModel.isUsernameLostAfterVoting())
    }

    @Test
    fun usernameRequestedMalformed_isNeitherLockedNorLost() = runVmTest {
        givenRequestedName(IdentityCreationState.VOTING, requested = "NOT_A_STATUS")
        val viewModel = viewModel()

        assertFalse(viewModel.isUsernameLocked())
        assertFalse(viewModel.isUsernameLostAfterVoting())
    }

    @Test
    fun usernameRequestedLocked_isLocked() = runVmTest {
        givenRequestedName(IdentityCreationState.VOTING, requested = UsernameRequestStatus.LOCKED.name)
        val viewModel = viewModel()

        assertTrue(viewModel.isUsernameLocked())
        assertFalse(viewModel.isUsernameLostAfterVoting())
    }

    @Test
    fun usernameRequestedLostVote_isLostAfterVoting() = runVmTest {
        givenRequestedName(IdentityCreationState.VOTING, requested = UsernameRequestStatus.LOST_VOTE.name)
        val viewModel = viewModel()

        assertTrue(viewModel.isUsernameLostAfterVoting())
        assertFalse(viewModel.isUsernameLocked())
    }

    // ── submit: reuse of an already registered identity (master #1567) ──────
    //
    // Reuse keeps the identity and registers only the new name, which on this
    // branch also means the legacy L1 path rather than the shielded service.

    @Test
    fun submit_usernameStepFailedAfterIdentityRegistered_reusesTheIdentity() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
        viewModel.identity = identity(IdentityCreationState.USERNAME_REGISTERING, errorMessage = "domain document rejected")

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        verify(exactly = 0) { shieldedUsernameCreation.submit(any(), any()) }
    }

    @Test
    fun submit_failedBeforeIdentityRegistered_doesNotReuse() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
        // the identity itself never made it, so there is nothing to reuse
        viewModel.identity = identity(IdentityCreationState.UPGRADING_WALLET, errorMessage = "asset lock failed")

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { shieldedUsernameCreation.submit("alice2", null) }
    }

    @Test
    fun submit_identityRegisteredWithoutError_doesNotReuse() = runVmTest {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
        viewModel.identity = identity(IdentityCreationState.IDENTITY_REGISTERED, errorMessage = null)

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { shieldedUsernameCreation.submit("alice2", null) }
    }

    private companion object {
        const val CHECKED_NAME = "dashuser"
        val OTHER_IDENTITY: Identifier = Identifier.from(ByteArray(32) { 1 })
    }

    private fun viewModelCheckingWith(document: Document?, contenders: Contenders): RequestUserNameViewModel {
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername(CHECKED_NAME) } returns Resource.success(document)
            every { getVoteContendersOrThrow(CHECKED_NAME) } returns contenders
        }
        return viewModel(platformRepo)
    }

    private suspend fun checkUsernameWith(document: Document?, contenders: Contenders): RequestUserNameUIState {
        val viewModel = viewModelCheckingWith(document, contenders)
        viewModel.checkUsername(CHECKED_NAME)
        return viewModel.uiState.first { it.usernameCheckSuccess || it.usernameCheckFailed }
    }

    private fun contenders(
        winner: ContestedDocumentVotePollWinnerInfo? = null,
        map: Map<Identifier, ContenderWithSerializedDocument> = mapOf(),
        lockVoteTally: Int = 0
    ): Contenders = Contenders(
        Optional.ofNullable(winner?.let { Pair(it, mockk<BlockInfo>()) }),
        map,
        0,
        lockVoteTally
    )

    private fun contender(votes: Int): ContenderWithSerializedDocument = mockk {
        every { this@mockk.votes } returns votes
        // no document, so the voting period start stays unknown and no deserializing is needed
        every { serializedDocument } returns null
    }

    private fun finishedVote(isLocked: Boolean, noWinner: Boolean = false): ContestedDocumentVotePollWinnerInfo =
        mockk {
            every { this@mockk.isLocked } returns isLocked
            every { this@mockk.noWinner } returns noWinner
        }

    private fun givenRequestedName(creationState: IdentityCreationState, requested: String?) {
        coEvery { identityConfig.get(BlockchainIdentityConfig.USERNAME) } returns "alice2"
        coEvery { identityConfig.get(BlockchainIdentityConfig.CREATION_STATE) } returns creationState.name
        coEvery { identityConfig.get(BlockchainIdentityConfig.USERNAME_REQUESTED) } returns requested
    }

    private fun identity(creationState: IdentityCreationState, errorMessage: String?) = BlockchainIdentityData(
        creationState,
        errorMessage,
        "alice2",
        null,
        "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr",
        false
    )
}
