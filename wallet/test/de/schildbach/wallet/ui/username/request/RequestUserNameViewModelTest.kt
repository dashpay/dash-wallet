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
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.service.platform.sdk.SdkShieldedUsernameCreation
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameCreationOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameNameStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameSubmitState
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.junit.After
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

    private val identityConfig = mockk<BlockchainIdentityConfig> {
        every { observe(BlockchainIdentityConfig.IDENTITY_ID) } returns emptyFlow()
        coEvery { get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK) } returns null
        coEvery { get(BlockchainIdentityConfig.USERNAME) } returns null
        coEvery { set(BlockchainIdentityConfig.USERNAME, any()) } just Runs
        coEvery { set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, any()) } just Runs
    }

    private val walletData = mockk<WalletDataProvider> {
        every { observeBalance(any(), any()) } returns emptyFlow()
    }

    private val shieldedSyncStatusFlow = MutableStateFlow(ShieldedSyncStatus.NOT_READY)
    private val shieldedBalanceFlow = MutableStateFlow(Dash.ZERO)
    private val shieldedBalanceService = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns true
        every { observeShieldedBalance() } returns shieldedBalanceFlow
        every { shieldedSyncStatus } returns shieldedSyncStatusFlow
    }

    private fun viewModel() = RequestUserNameViewModel(
        walletApplication = walletApplication,
        identityConfig = identityConfig,
        walletData = walletData,
        platformRepo = mockk<PlatformRepo>(relaxed = true),
        usernameRequestDao = mockk<UsernameRequestDao>(relaxed = true),
        analytics = mockk<AnalyticsService>(relaxed = true),
        topUpRepository = mockk<TopUpRepository>(relaxed = true),
        shieldedUsernameCreation = shieldedUsernameCreation,
        shieldedBalanceService = shieldedBalanceService
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
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
        verify(exactly = 1) { shieldedUsernameCreation.acknowledge() }
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
}
