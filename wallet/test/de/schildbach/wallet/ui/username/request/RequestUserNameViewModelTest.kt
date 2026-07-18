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
import de.schildbach.wallet.service.platform.sdk.SdkShieldedUsernameCreation
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameCreationOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameNameStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameSubmitState
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.IdentityCreationStatusHolder
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.username.UsernameType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val walletApplication = mockk<WalletApplication>(relaxed = true)

    private val creationStateFlow = MutableStateFlow<String?>(null)
    private val identityConfig = mockk<BlockchainIdentityConfig> {
        every { observe(BlockchainIdentityConfig.IDENTITY_ID) } returns emptyFlow()
        every { observe(BlockchainIdentityConfig.CREATION_STATE) } returns creationStateFlow
        coEvery { get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK) } returns null
        coEvery { get(BlockchainIdentityConfig.USERNAME) } returns null
        coEvery { set(BlockchainIdentityConfig.USERNAME, any()) } just Runs
        coEvery { set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, any()) } just Runs
    }

    private val walletData = mockk<WalletData> {
        every { observeBalance(any(), any()) } returns emptyFlow()
    }

    private val shieldedSyncStatusFlow = MutableStateFlow(ShieldedSyncStatus.NOT_READY)
    private val shieldedBalanceFlow = MutableStateFlow(Dash.ZERO)
    private val shieldedBalanceService = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns true
        every { observeShieldedBalance() } returns shieldedBalanceFlow
        every { shieldedSyncStatus } returns shieldedSyncStatusFlow
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

    private fun viewModel(
        platformRepo: PlatformRepo = mockk<PlatformRepo>(relaxed = true)
    ) = RequestUserNameViewModel(
        walletApplication = walletApplication,
        identityConfig = identityConfig,
        walletData = walletData,
        platformRepo = platformRepo,
        usernameRequestDao = mockk<UsernameRequestDao>(relaxed = true),
        analytics = mockk<AnalyticsService>(relaxed = true),
        topUpRepository = mockk<TopUpRepository>(relaxed = true),
        shieldedUsernameCreation = shieldedUsernameCreation,
        shieldedBalanceService = shieldedBalanceService,
        platformHealthProbe = platformHealthProbe,
        identityCreationStatus = IdentityCreationStatusHolder()
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

    // ── paymentSource routing ───────────────────────────────────────────────

    @Test
    fun submit_shieldedSource_routesToTheShieldedService_neverTheL1Service() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

        viewModel.submit()

        // submit() hops over Dispatchers.IO for the config write — poll.
        verify(exactly = 1, timeout = 5_000) { shieldedUsernameCreation.submit("alice2", null) }
        verify(exactly = 0) { walletApplication.startService(any()) }
    }

    @Test
    fun submit_dashSource_keepsTheLegacyL1PathUntouched() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.requestedUserName = "alice2"
        // DASH_BALANCE is the default paymentSource.

        viewModel.submit()

        verify(exactly = 1, timeout = 5_000) { walletApplication.startService(any()) }
        verify(exactly = 0) { shieldedUsernameCreation.submit(any(), any()) }
    }

    @Test
    fun l1Creation_terminalState_clearsTheProcessingDialog() = runTest(dispatcher) {
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
    fun l1Creation_terminalState_withNoSubmitInFlight_isIgnored() = runTest(dispatcher) {
        val viewModel = viewModel()
        // No submit() — a pre-existing DONE state (e.g. re-entering the screen)
        // must not fabricate a "submitted" completion.
        creationStateFlow.value = IdentityCreationState.DONE.name

        assertEquals(false, viewModel.uiState.value.usernameRequestSubmitting)
        assertEquals(false, viewModel.uiState.value.usernameRequestSubmitted)
    }

    @Test
    fun submit_reuseTransaction_staysLegacyEvenWithShieldedSource() = runTest(dispatcher) {
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

    // ── Submit-state mirroring ──────────────────────────────────────────────

    @Test
    fun shieldedProving_mirrorsAsSubmitting() = runTest(dispatcher) {
        val viewModel = viewModel()

        shieldedSubmitState.value = ShieldedUsernameSubmitState.Proving

        assertTrue(viewModel.uiState.value.usernameRequestSubmitting)
        assertFalse(viewModel.uiState.value.usernameSubmittedError)
    }

    @Test
    fun shieldedCreated_mirrorsAsSubmitted_andAcknowledges() = runTest(dispatcher) {
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
    fun shieldedNotSent_mirrorsAsError_andAcknowledgesForRetry() = runTest(dispatcher) {
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
        runTest(dispatcher) {
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
    fun shieldedNotSent_runtimeNotReady_alsoSurfacesTheCalmSyncingState() = runTest(dispatcher) {
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
    fun shieldedAmbiguous_mirrorsAsAmbiguous_neverTheRetryableError() = runTest(dispatcher) {
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
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            // "alice2" contains a 2-9 digit → non-contested.
            viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

            assertTrue(viewModel.uiState.value.enoughBalance)
        }

    @Test
    fun checkUsernameValid_dashSource_nonContestedName_staysL1Gated() = runTest(dispatcher) {
        val viewModel = viewModel()
        // Default DASH_BALANCE; the L1 balance is zero.

        viewModel.checkUsernameValid("alice2", de.schildbach.wallet.ui.username.UsernameType.Primary)

        assertFalse(viewModel.uiState.value.enoughBalance)
    }

    @Test
    fun checkUsernameValid_shieldedSource_contestedName_gatedOnTheContestedDenomination() =
        runTest(dispatcher) {
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
        runTest(dispatcher) {
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
        runTest(dispatcher) {
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
        runTest(dispatcher) {
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
        runTest(dispatcher) {
            // The insufficient-balance row must name the 0.3 funding
            // denomination the contested creation actually needs, not the
            // 0.25 fee (or a hardcoded L1 amount).
            shieldedSyncStatusFlow.value = ShieldedSyncStatus.READY
            shieldedBalanceFlow.value = Dash(10_000_000L) // 0.1 DASH — not enough
            val viewModel = viewModel()
            viewModel.paymentSource = UsernamePaymentSource.SHIELDED_BALANCE

            viewModel.checkUsernameValid("alice", de.schildbach.wallet.ui.username.UsernameType.Primary)

            val state = viewModel.uiState.value
            assertFalse(state.enoughBalance)
            assertEquals(Dash(30_000_000L).toPlainString(), state.requiredAmount)
        }

    // ── Never-silent submits ────────────────────────────────────────────────

    @Test
    fun submit_shieldedRefused_surfacesTheErrorState_neverSilent() = runTest(dispatcher) {
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
        runTest(dispatcher) {
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
    fun checkUsername_lookupFailure_failsClosed_neverReadsAsAvailable() = runTest(dispatcher) {
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
    fun checkUsername_contendersFailure_failsClosed_neverReadsAsNotContested() = runTest(dispatcher) {
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
    fun checkUsername_retriggerAfterFailure_clearsTheFailedStateAndCompletes() = runTest(dispatcher) {
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            every { getUsername("alice") } returns
                Resource.error("DAPI timeout", null) andThen Resource.success(null)
            every { getVoteContendersOrThrow("alice") } returns mockk {
                every { map } returns emptyMap()
                every { lockVoteTally } returns 0
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

    // ── Advisory network-health warning ─────────────────────────────────────

    @Test
    fun checkNetworkHealth_degraded_setsNetworkSlow_neverGatesTheButtonInputs() = runTest(dispatcher) {
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
    fun checkNetworkHealth_unknown_showsNoWarning() = runTest(dispatcher) {
        coEvery { platformHealthProbe.probe() } returns PlatformHealth.UNKNOWN
        val viewModel = viewModel()

        viewModel.checkNetworkHealth()

        assertFalse(viewModel.uiState.value.networkSlow)
    }

    @Test
    fun reset_preservesTheNetworkSlowAdvisory() = runTest(dispatcher) {
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
    fun submit_dashSource_showsAProcessingStatus() = runTest(dispatcher) {
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
}
