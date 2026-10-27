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

package org.dash.wallet.integrations.coinbase

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integrations.coinbase.model.SwapTradeUIModel
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseConversionPreviewViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.SendDashResponseState
import org.dash.wallet.integrations.coinbase.viewmodels.TransferDashViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A Coinbase send whose outcome is unknown ([PaymentSubmissionPendingException]) may already be
 * on the network. Reporting it as a failure offers a retry, and a retry can pay twice, so both
 * Coinbase send paths must surface it as its own outcome-unknown result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentOutcomeUnknownTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val outcomeUnknown = PaymentSubmissionPendingException("unknown", IllegalStateException("timeout"))

    private val sendPaymentService = mockk<SendPaymentService>()
    private val coinBaseRepository = mockk<CoinBaseRepositoryInt>(relaxed = true)
    private val walletDataProvider = mockk<WalletDataProvider>(relaxed = true)
    private val networkState = mockk<NetworkStateInt>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } throws outcomeUnknown
        every { networkState.isConnected } returns MutableStateFlow(true)
        every { walletDataProvider.observeTotalBalance() } returns emptyFlow()
        coEvery { coinBaseRepository.getUserAccountAddress() } returns failure()
        coEvery { coinBaseRepository.getExchangeRateFromCoinbase() } returns failure()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun transferDash_outcomeUnknown_isOutcomeUnknownState_notARetryableFailure() = runTest {
        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        every { walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        val viewModel = TransferDashViewModel(
            coinBaseRepository,
            mockk(relaxed = true),
            walletDataProvider,
            sendPaymentService,
            mockk(relaxed = true),
            networkState,
            mockk(relaxed = true),
            mockk(relaxed = true),
            walletUIConfig
        )

        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false)

        assertEquals(
            SendDashResponseState.OutcomeUnknownState,
            viewModel.observeSendDashToCoinbaseState.value
        )
    }

    @Test
    fun sellDash_outcomeUnknown_raisesOutcomeUnknown_neverFailureNorSuccess() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        val viewModel = CoinbaseConversionPreviewViewModel(
            coinBaseRepository,
            walletDataProvider,
            sendPaymentService,
            mockk(relaxed = true),
            networkState,
            mockk(relaxed = true)
        )
        var outcomeUnknownEvents = 0
        var failureEvents = 0
        var successEvents = 0
        viewModel.onPaymentOutcomeUnknown.observeForever { outcomeUnknownEvents++ }
        viewModel.onFailure.observeForever { failureEvents++ }
        viewModel.sellSwapSuccessState.observeForever { successEvents++ }

        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertEquals(1, outcomeUnknownEvents)
        assertEquals(0, failureEvents)
        assertEquals(0, successEvents)
    }

    private fun <T> failure(): ResponseResource<T> =
        ResponseResource.Failure(IllegalStateException("offline"), false, 0, null)
}
