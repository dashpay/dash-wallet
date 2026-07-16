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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.shieldedIdentityFundingRequirement
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.money.Dash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the shielded-funds payment step of the create-username
 * flow ([UsernamePaymentViewModel]): the balance/sync/flag → prompt mapping
 * (Figma flow canvas 555:811) and the graceful degrade with
 * `USE_KOTLIN_SDK_SHIELDED` off — the sheets must never appear and nothing
 * may crash or even touch the shielded runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsernamePaymentViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Fee for a non-contested username, as the ViewModel sources it. */
    private val fee = Dash(Constants.DASH_PAY_FEE.value)

    /**
     * The shielded pool balance actually required: the smallest fixed
     * Type-20 denomination covering the fee (0.03 DASH → 0.1 DASH) — the
     * identity is funded by spending a whole denomination, so affordability
     * is denomination-based, not fee-based.
     */
    private val requirement = shieldedIdentityFundingRequirement(fee)!!

    private val balanceFlow = MutableStateFlow(Dash.ZERO)
    private val statusFlow = MutableStateFlow(ShieldedSyncStatus.NOT_READY)

    private val shieldedBalanceService = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns true
        every { observeShieldedBalance() } returns balanceFlow
        every { shieldedSyncStatus } returns statusFlow
    }

    private fun configWithFlag(enabled: Boolean?) = mockk<DashPayConfig> {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns enabled
    }

    private val walletData = mockk<org.dash.wallet.common.WalletDataProvider> {
        every { observeTotalBalance() } returns emptyFlow()
    }

    private fun viewModel(flag: Boolean? = true) =
        UsernamePaymentViewModel(configWithFlag(flag), shieldedBalanceService, walletData)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Pure requirement-gating logic ───────────────────────────────────────

    @Test
    fun state_flagOff_promptIsNone_regardlessOfBalance() {
        val state = UsernamePaymentUIState(
            shieldedEnabled = false,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(fee.duffs * 10),
            usernameFee = fee
        )
        assertEquals(UsernamePaymentPrompt.NONE, state.prompt)
        assertFalse(state.canPayFeeFromShielded)
    }

    @Test
    fun state_balanceCoversDenomination_andReady_promptsPaymentSelection() {
        val state = UsernamePaymentUIState(
            shieldedEnabled = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = requirement, // exactly the funding denomination is enough
            usernameFee = fee
        )
        assertEquals(requirement, state.shieldedFundingRequirement)
        assertTrue(state.canPayFeeFromShielded)
        assertEquals(UsernamePaymentPrompt.SELECT_PAYMENT_OPTION, state.prompt)
    }

    @Test
    fun state_balanceCoversFeeButNotDenomination_promptsMakePrivate() {
        // Denomination affordability, not just fee: a pool covering the
        // 0.03 fee but not the 0.1 DASH Type-20 denomination the creation
        // actually spends must not unlock the shielded option.
        val state = UsernamePaymentUIState(
            shieldedEnabled = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(requirement.duffs - 1),
            usernameFee = fee
        )
        assertTrue(state.shieldedBalance >= fee)
        assertFalse(state.canPayFeeFromShielded)
        assertEquals(UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE, state.prompt)
    }

    @Test
    fun state_balanceBelowFee_promptsMakePrivate() {
        val state = UsernamePaymentUIState(
            shieldedEnabled = true,
            syncStatus = ShieldedSyncStatus.READY,
            shieldedBalance = Dash(fee.duffs - 1),
            usernameFee = fee
        )
        assertFalse(state.canPayFeeFromShielded)
        assertEquals(UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE, state.prompt)
    }

    @Test
    fun state_balanceNotTrustworthyWhileSyncing_neverUnlocksShieldedPayment() {
        // A funded wallet mid-sync reads Dash.ZERO or a stale value — the
        // rule from the More-screen balance card: only READY is trusted.
        for (status in listOf(ShieldedSyncStatus.NOT_READY, ShieldedSyncStatus.SYNCING)) {
            val state = UsernamePaymentUIState(
                shieldedEnabled = true,
                syncStatus = status,
                shieldedBalance = Dash(fee.duffs * 10),
                usernameFee = fee
            )
            assertFalse("status=$status", state.shieldedBalanceTrustworthy)
            assertFalse("status=$status", state.canPayFeeFromShielded)
            assertEquals("status=$status", UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE, state.prompt)
        }
    }

    @Test
    fun state_continueRequiresASelection() {
        val state = UsernamePaymentUIState(shieldedEnabled = true)
        assertFalse(state.canContinue)
        assertTrue(state.copy(selectedSource = UsernamePaymentSource.SHIELDED_BALANCE).canContinue)
        assertTrue(state.copy(selectedSource = UsernamePaymentSource.DASH_BALANCE).canContinue)
    }

    // ── ViewModel wiring ────────────────────────────────────────────────────

    @Test
    fun flagOff_staysInert_andNeverTouchesTheShieldedRuntime() = runTest(dispatcher) {
        val viewModel = viewModel(flag = false)

        val state = viewModel.uiState.value
        assertFalse(state.shieldedEnabled)
        assertEquals(UsernamePaymentPrompt.NONE, state.prompt)
        coVerify(exactly = 0) { shieldedBalanceService.ensureShieldedReady() }
    }

    @Test
    fun flagUnset_treatedAsOff() = runTest(dispatcher) {
        val viewModel = viewModel(flag = null)

        assertFalse(viewModel.uiState.value.shieldedEnabled)
        assertEquals(UsernamePaymentPrompt.NONE, viewModel.uiState.value.prompt)
    }

    @Test
    fun flagOn_bringsRuntimeUp_andMirrorsBalanceAndStatusLive() = runTest(dispatcher) {
        val viewModel = viewModel(flag = true)

        coVerify(exactly = 1) { shieldedBalanceService.ensureShieldedReady() }

        // Initially not ready: no shielded payment, "make private" arm.
        assertEquals(UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE, viewModel.uiState.value.prompt)

        // The pool syncs and lands a covering balance (≥ the denomination).
        balanceFlow.value = Dash(requirement.duffs * 2)
        statusFlow.value = ShieldedSyncStatus.READY

        val state = viewModel.uiState.value
        assertEquals(Dash(requirement.duffs * 2), state.shieldedBalance)
        assertEquals(ShieldedSyncStatus.READY, state.syncStatus)
        assertEquals(fee, state.usernameFee)
        assertTrue(state.canPayFeeFromShielded)
        assertEquals(UsernamePaymentPrompt.SELECT_PAYMENT_OPTION, state.prompt)
    }

    @Test
    fun flagOn_balanceDrainedBelowFee_fallsBackToMakePrivate() = runTest(dispatcher) {
        val viewModel = viewModel(flag = true)

        balanceFlow.value = Dash(requirement.duffs * 2)
        statusFlow.value = ShieldedSyncStatus.READY
        assertEquals(UsernamePaymentPrompt.SELECT_PAYMENT_OPTION, viewModel.uiState.value.prompt)

        balanceFlow.value = Dash.ZERO
        assertEquals(UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE, viewModel.uiState.value.prompt)
    }

    @Test
    fun selectSource_updatesStateAndUnlocksContinue() = runTest(dispatcher) {
        val viewModel = viewModel(flag = true)

        assertFalse(viewModel.uiState.value.canContinue)
        viewModel.selectSource(UsernamePaymentSource.SHIELDED_BALANCE)
        assertEquals(UsernamePaymentSource.SHIELDED_BALANCE, viewModel.uiState.value.selectedSource)
        assertTrue(viewModel.uiState.value.canContinue)

        viewModel.selectSource(UsernamePaymentSource.DASH_BALANCE)
        assertEquals(UsernamePaymentSource.DASH_BALANCE, viewModel.uiState.value.selectedSource)
    }

    @Test
    fun failuresNeverCrashTheFlow() = runTest(dispatcher) {
        val throwingService = mockk<ShieldedBalanceService> {
            coEvery { ensureShieldedReady() } throws IllegalStateException("native bring-up failed")
            every { observeShieldedBalance() } returns flow { throw IllegalStateException("store gone") }
            every { shieldedSyncStatus } returns statusFlow
        }

        val viewModel = UsernamePaymentViewModel(configWithFlag(true), throwingService, walletData)

        // Enabled, but the balance stays untrusted zero → the safe arm.
        val state = viewModel.uiState.value
        assertTrue(state.shieldedEnabled)
        assertEquals(Dash.ZERO, state.shieldedBalance)
        assertEquals(UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE, state.prompt)
    }

    @Test
    fun configFailure_degradesToDisabled() = runTest(dispatcher) {
        val throwingConfig = mockk<DashPayConfig> {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } throws IllegalStateException("datastore")
        }

        val viewModel = UsernamePaymentViewModel(throwingConfig, shieldedBalanceService, walletData)

        assertFalse(viewModel.uiState.value.shieldedEnabled)
        assertEquals(UsernamePaymentPrompt.NONE, viewModel.uiState.value.prompt)
        coVerify(exactly = 0) { shieldedBalanceService.ensureShieldedReady() }
    }
}
