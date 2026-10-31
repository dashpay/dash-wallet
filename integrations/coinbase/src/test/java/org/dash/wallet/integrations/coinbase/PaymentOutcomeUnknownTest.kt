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
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any())
        } throws outcomeUnknown
        every { sendPaymentService.currentWalletSession() } returns null
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
        val viewModel = transferViewModel(SavedStateHandle())

        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)

        assertEquals(
            SendDashResponseState.OutcomeUnknownState,
            viewModel.observeSendDashToCoinbaseState.value
        )
    }

    @Test
    fun transferDash_outcomeUnknown_isTerminal_andRefusesASecondSend() = runTest {
        val viewModel = transferViewModel(SavedStateHandle())

        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)
        assertTrue(viewModel.paymentOutcomeUnknown.value)

        // Continue again on the same screen: refused before anything is sent.
        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)

        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
    }

    @Test
    fun transferDash_outcomeUnknown_survivesRestore_andStillRefusesASend() = runTest {
        val savedState = SavedStateHandle()
        transferViewModel(savedState)
            .sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)

        // A new view model over the saved state, as after the process was killed and restored.
        val restored = transferViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)

        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
    }

    @Test
    fun transferDash_outcomeUnknown_aNewlyOpenedTransferScreen_mayTransferAgain() = runTest {
        // The view model is scoped to the whole Coinbase graph, so it outlives the transfer screen.
        val viewModel = transferViewModel(SavedStateHandle())
        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)
        assertTrue(viewModel.paymentOutcomeUnknown.value)

        // The user leaves and later opens the transfer screen afresh: a new transfer, not a retry.
        viewModel.startNewTransfer()

        assertFalse(viewModel.paymentOutcomeUnknown.value)
        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, checkConditions = false, session = null)
        coVerify(exactly = 2) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
    }

    @Test
    fun transferDash_concurrentSends_onlyOneReachesTheWallet_andARetryableFailureAllowsAnother() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any())
        } coAnswers {
            gate.await()
            throw IllegalStateException("rejected")
        }
        val viewModel = transferViewModel(SavedStateHandle())

        val first = launch { viewModel.sendDash(Dash.valueOf(100_000), false, false, null) }
        val second = launch { viewModel.sendDash(Dash.valueOf(100_000), false, false, null) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()

        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
        assertFalse(viewModel.paymentOutcomeUnknown.value)

        // The send was refused (a retryable failure): the user may try again.
        viewModel.sendDash(Dash.valueOf(100_000), false, false, null)
        coVerify(exactly = 2) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
    }

    @Test
    fun sellDash_outcomeUnknown_isTerminal_neverFailureNorSuccess_andRefusesASecondCommit() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        val savedState = SavedStateHandle()
        val viewModel = previewViewModel(savedState)
        var failureEvents = 0
        var successEvents = 0
        viewModel.onFailure.observeForever { failureEvents++ }
        viewModel.sellSwapSuccessState.observeForever { successEvents++ }

        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertTrue(viewModel.paymentOutcomeUnknown.value)
        assertEquals(0, failureEvents)
        assertEquals(0, successEvents)

        // Confirm again on the same screen: refused before any commit or send.
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        coVerify(exactly = 1) { coinBaseRepository.commitSwapTrade(any()) }
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
    }

    @Test
    fun sellDash_outcomeUnknown_survivesRestore_andStillRefusesACommit() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        val savedState = SavedStateHandle()
        previewViewModel(savedState).commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        // A new view model over the saved state, as after the process was killed and restored.
        val restored = previewViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        coVerify(exactly = 1) { coinBaseRepository.commitSwapTrade(any()) }
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any(), any()) }
    }

    @Test
    fun sellDash_concurrentConfirms_onlyOneCommits_andARetryableFailureAllowsAnother() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { coinBaseRepository.commitSwapTrade(any()) } coAnswers {
            gate.await()
            failure()
        }
        val viewModel = previewViewModel(SavedStateHandle())

        // Two rapid Confirm taps: the first is still committing when the second arrives.
        val first = viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01")
        val second = viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01")
        gate.complete(Unit)
        first.join()
        second.join()

        coVerify(exactly = 1) { coinBaseRepository.commitSwapTrade(any()) }
        assertFalse(viewModel.paymentOutcomeUnknown.value)

        // The commit failed (retryable): a later Confirm is accepted again.
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()
        coVerify(exactly = 2) { coinBaseRepository.commitSwapTrade(any()) }
    }

    private fun restoredFrom(savedState: SavedStateHandle) =
        SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })

    private fun transferViewModel(savedState: SavedStateHandle): TransferDashViewModel {
        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        every { walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        return TransferDashViewModel(
            coinBaseRepository,
            mockk(relaxed = true),
            walletDataProvider,
            sendPaymentService,
            mockk(relaxed = true),
            networkState,
            mockk(relaxed = true),
            mockk(relaxed = true),
            walletUIConfig,
            savedState
        )
    }

    private fun previewViewModel(savedState: SavedStateHandle) = CoinbaseConversionPreviewViewModel(
        coinBaseRepository,
        walletDataProvider,
        sendPaymentService,
        mockk(relaxed = true),
        networkState,
        mockk(relaxed = true),
        savedState
    )

    private fun <T> failure(): ResponseResource<T> =
        ResponseResource.Failure(IllegalStateException("offline"), false, 0, null)
}
