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

import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ContestedUsernameFees
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.ui.username.UsernameType
import de.schildbach.wallet.util.toNeutralCoin
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ConfirmUserNameDialogViewModel]: the amount the user approves in the
 * confirm sheet must be the DASH amount the tap incurs — independent of fiat
 * data, and computed from the inputs the dialog actually applied. Confirmation
 * is unavailable ([ConfirmUserNameUIState.amountReady] false) until it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmUserNameDialogViewModelTest {

    private val createdViewModels = mutableListOf<ConfirmUserNameDialogViewModel>()

    private val contestedShieldedInputs = ConfirmUserNameInputs(
        usernameType = UsernameType.Primary,
        isContestableUsername = true,
        hasIdentity = false,
        paymentSource = UsernamePaymentSource.SHIELDED_BALANCE
    )

    private val contestedL1Inputs = contestedShieldedInputs.copy(paymentSource = UsernamePaymentSource.DASH_BALANCE)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun dash(amount: Coin) = MonetaryFormat.BTC.noCode().format(amount.toNeutralCoin()).toString()

    private fun viewModel(
        selectedCurrency: Flow<String?> = flowOf("USD"),
        exchangeRate: Flow<ExchangeRate?> = flowOf(ExchangeRate("USD", "30.00")),
        protocolVersion: suspend () -> Int? = { 14 }
    ): ConfirmUserNameDialogViewModel {
        val walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns selectedCurrency
        }
        val exchangeRatesProvider = mockk<ExchangeRatesProvider> {
            every { observeExchangeRate(any()) } returns exchangeRate
        }
        val dashSdkService = mockk<DashSdkService> {
            coEvery { currentProtocolVersion() } coAnswers { protocolVersion() }
        }
        return ConfirmUserNameDialogViewModel(
            mockk<Configuration>(relaxed = true),
            exchangeRatesProvider,
            walletUIConfig,
            dashSdkService
        ).also { createdViewModels += it }
    }

    @Test
    fun `the DASH amount is ready without an exchange rate`() {
        val vm = viewModel(exchangeRate = flowOf(null))

        vm.setInputs(contestedL1Inputs)

        val state = vm.uiState.value
        assertTrue(state.amountReady)
        assertEquals(dash(ContestedUsernameFees.CURRENT.contested), state.amountStr)
        assertEquals("", state.fiatAmountStr)
        assertEquals("", state.fiatSymbol)
    }

    @Test
    fun `the DASH amount is ready without a selected currency`() {
        val vm = viewModel(selectedCurrency = flowOf(null))

        vm.setInputs(contestedL1Inputs)

        assertTrue(vm.uiState.value.amountReady)
        assertEquals(dash(ContestedUsernameFees.CURRENT.contested), vm.uiState.value.amountStr)
    }

    @Test
    fun `the DASH amount is ready while the fiat sources have not emitted yet`() {
        val vm = viewModel(selectedCurrency = MutableSharedFlow(), exchangeRate = MutableSharedFlow())

        vm.setInputs(contestedL1Inputs)

        assertTrue(vm.uiState.value.amountReady)
        assertEquals(dash(ContestedUsernameFees.CURRENT.contested), vm.uiState.value.amountStr)
    }

    @Test
    fun `the amount is not ready before the dialog applies its inputs`() {
        val vm = viewModel()

        val state = vm.uiState.value
        assertFalse("a cached rate and resolved fees alone must not enable confirmation", state.amountReady)
        assertEquals("", state.amountStr)
    }

    @Test
    fun `the amount is not ready before the contested fee is resolved`() {
        val protocolVersion = CompletableDeferred<Int?>()
        val vm = viewModel(protocolVersion = { protocolVersion.await() })

        vm.setInputs(contestedL1Inputs)
        assertFalse(vm.uiState.value.amountReady)

        protocolVersion.complete(13)

        assertTrue(vm.uiState.value.amountReady)
        assertEquals(dash(ContestedUsernameFees.LEGACY.contested), vm.uiState.value.amountStr)
    }

    @Test
    fun `inputs applied after a cached-rate emission recompute the contested shielded amount`() {
        val vm = viewModel()
        // Rate and fees have both emitted already; only the inputs are missing.
        assertFalse(vm.uiState.value.amountReady)

        vm.setInputs(contestedShieldedInputs)

        val state = vm.uiState.value
        assertTrue(state.amountReady)
        // The 0.25 DASH shielded exit denomination, not the default primary
        // non-contested 0.03 DASH fee.
        assertEquals(dash(Coin.valueOf(25_000_000)), state.amountStr)
        assertTrue(state.fromShieldedBalance)
        assertTrue("fiat decoration is filled when a rate exists", state.fiatAmountStr.isNotEmpty())
    }

    @Test
    fun `changed inputs recompute the amount`() {
        val vm = viewModel()
        vm.setInputs(contestedShieldedInputs)

        vm.setInputs(
            contestedL1Inputs.copy(isContestableUsername = false)
        )

        val state = vm.uiState.value
        assertTrue(state.amountReady)
        assertEquals(dash(Constants.DASH_PAY_FEE), state.amountStr)
        assertFalse(state.fromShieldedBalance)
    }
}
