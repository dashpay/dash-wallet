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

import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Coin
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the SdkWriteResult → UI contract of [ShieldedTransferViewModel]:
 * Broadcast → Success, NotBroadcast → NotSent (retryable), Ambiguous →
 * MayHaveGoneThrough (terminal — the UI never offers a retry), plus the
 * amount/balance gating of the Continue button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedTransferViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val shieldedService = mockk<ShieldedBalanceService> {
        coEvery { ensureShieldedReady() } returns true
        every { observeShieldedBalance() } returns flowOf(Dash.parse("15.5"))
    }
    private val walletData = mockk<WalletDataProvider> {
        every { observeTotalBalance() } returns flowOf(Coin.parseCoin("3.00"))
        every { freshReceiveAddressString() } returns "yTestAddressBase58"
    }
    private val walletUIConfig = mockk<WalletUIConfig> {
        every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns flowOf("USD")
    }
    private val exchangeRates = mockk<ExchangeRatesProvider> {
        every { observeExchangeRate(any()) } returns flowOf(null)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ShieldedTransferViewModel =
        ShieldedTransferViewModel(shieldedService, walletData, walletUIConfig, exchangeRates)
            .apply { ioDispatcher = dispatcher }

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
    fun broadcast_mapsToSuccess() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromCredits(any()) } returns SdkWriteResult.Broadcast(Unit)
        val vm = viewModel()

        vm.typeAmountAndConfirm()

        assertEquals(ShieldedSubmitState.Success, vm.uiState.value.submitState)
    }

    @Test
    fun notBroadcast_mapsToNotSent_andAllowsRetry() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromCredits(any()) } returns
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
        coEvery { shieldedService.shieldFromCredits(any()) } returns
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
}
