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
import org.dash.wallet.integrations.coinbase.ui.dialogs.CoinBaseResultDialog
import org.dash.wallet.integrations.coinbase.ui.resultDialogType
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseConversionPreviewViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.SendDashResponseState
import org.dash.wallet.integrations.coinbase.viewmodels.TransferDashViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
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

        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)

        assertEquals(
            SendDashResponseState.OutcomeUnknownState,
            viewModel.observeSendDashToCoinbaseState.value
        )
    }

    @Test
    fun transferDash_outcomeUnknown_isTerminal_andRefusesASecondSend() = runTest {
        val viewModel = transferViewModel(SavedStateHandle())

        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)
        assertTrue(viewModel.paymentOutcomeUnknown.value)

        // Continue again on the same screen: refused before anything is sent.
        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)

        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_outcomeUnknown_survivesRestore_andStillRefusesASend() = runTest {
        val savedState = SavedStateHandle()
        transferViewModel(savedState)
            .sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)

        // A new view model over the saved state, as after the process was killed and restored.
        val restored = transferViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)

        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_outcomeUnknown_aNewlyOpenedTransferScreen_mayTransferAgain() = runTest {
        // The view model is scoped to the whole Coinbase graph, so it outlives the transfer screen.
        val viewModel = transferViewModel(SavedStateHandle())
        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)
        assertTrue(viewModel.paymentOutcomeUnknown.value)

        // The user leaves and later opens the transfer screen afresh: a new transfer, not a retry.
        viewModel.startNewTransfer()

        assertFalse(viewModel.paymentOutcomeUnknown.value)
        viewModel.sendDash(Dash.valueOf(100_000), isEmptyWallet = false, session = null)
        coVerify(exactly = 2) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_theUiWaiterCancelledDuringTheSend_isAnUnknownOutcome_notARetry() = runTest {
        // A recreation used to cancel the fragment coroutine that ran the send while it may
        // already have gone out: it was reported as a retryable failure.
        val held = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            held.await()
            "txid"
        }
        val viewModel = transferViewModel(SavedStateHandle())

        val waiter = launch { viewModel.sendDash(Dash.valueOf(100_000), false, null) }
        runCurrent()
        waiter.cancel()
        advanceUntilIdle()

        assertTrue(viewModel.paymentOutcomeUnknown.value)
        assertFalse(viewModel.observeSendDashToCoinbaseState.value is SendDashResponseState.FailureState)
        viewModel.sendDash(Dash.valueOf(100_000), false, null)
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_aSendInProgress_whenTheProcessDies_isAnUnknownOutcome_onRestore() = runTest {
        val held = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            held.await()
            "txid"
        }
        val savedState = SavedStateHandle()
        val viewModel = transferViewModel(savedState)
        val sending = launch { viewModel.sendDash(Dash.valueOf(100_000), false, null) }
        runCurrent()

        // The process is killed now; a new view model starts from the state saved meanwhile.
        val restored = transferViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.sendDash(Dash.valueOf(100_000), false, null)

        held.complete(Unit)
        sending.join()
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_aSubmissionOwnedByTheViewModel_outlivesTheScreen_andCannotBeSentAgain() = runTest {
        val held = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            held.await()
            "txid"
        }
        val viewModel = transferViewModel(SavedStateHandle())

        assertTrue(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        // A second Continue, from this screen or a recreated one, while the first is sending.
        assertFalse(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))

        held.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.observeSendDashToCoinbaseState.value is SendDashResponseState.SuccessState)
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_aSuccessHeldWhileLocked_whenTheProcessDies_refusesTheTransferOnRestore() = runTest {
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val savedState = SavedStateHandle()
        val viewModel = transferViewModel(savedState)

        // Sent; the result waits for the screen (the wallet is locked) when the process is killed.
        assertTrue(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        advanceUntilIdle()
        assertTrue(viewModel.observeSendDashToCoinbaseState.value is SendDashResponseState.SuccessState)

        val restored = transferViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        assertFalse(restored.submitTransfer(Dash.valueOf(100_000), false, null))
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_aSuccessShownButNotAnswered_isUnknownOnRestore_andAnsweredIsNot() = runTest {
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val savedState = SavedStateHandle()
        val viewModel = transferViewModel(savedState)

        assertTrue(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        advanceUntilIdle()
        val result = requireNotNull(viewModel.observeSendDashToCoinbaseState.value)
        assertTrue(viewModel.consumeSendDashToCoinbaseState(result))

        // The result dialog is up when the process dies: the transfer must not be sendable again.
        assertTrue(transferViewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)

        viewModel.acknowledgeTransferResult()
        assertFalse(transferViewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
    }

    @Test
    fun transferDash_aSentTransfer_isASuccess_evenWhenTheWalletDoesNotListItAsPending() = runTest {
        // The wallet may not have the transaction yet, or it may be confirmed already.
        every { walletDataProvider.getTransaction(any()) } returns null
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val viewModel = transferViewModel(SavedStateHandle())

        assertTrue(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        advanceUntilIdle()

        val result = requireNotNull(viewModel.observeSendDashToCoinbaseState.value)
        assertEquals(SendDashResponseState.SuccessState("txid"), result)
        assertEquals(CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS, result.resultDialogType())
        assertEquals(
            CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR,
            SendDashResponseState.FailureState("rejected").resultDialogType()
        )
    }

    @Test
    fun transferDash_aSecondContinue_betweenASuccessAndItsAnswer_isRefused() = runTest {
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val viewModel = transferViewModel(SavedStateHandle())

        assertTrue(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        advanceUntilIdle()
        assertTrue(viewModel.observeSendDashToCoinbaseState.value is SendDashResponseState.SuccessState)

        assertFalse(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        viewModel.sendDash(Dash.valueOf(100_000), false, null)
        advanceUntilIdle()
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun transferDash_aSendFinishingAfterANewTransferOpened_isNotShownOnIt() = runTest {
        val viewModel = transferViewModel(SavedStateHandle())

        // An old transfer's result, or its unknown outcome, is not shown on a new transfer screen.
        val again = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            again.await()
            throw outcomeUnknown
        }
        assertTrue(viewModel.submitTransfer(Dash.valueOf(100_000), false, null))
        runCurrent()
        viewModel.startNewTransfer()
        again.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.observeSendDashToCoinbaseState.value)
        assertFalse(viewModel.paymentOutcomeUnknown.value)
    }

    @Test
    fun transferDash_concurrentSends_onlyOneReachesTheWallet_andARetryableFailureAllowsAnother() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            gate.await()
            throw IllegalStateException("rejected")
        }
        val viewModel = transferViewModel(SavedStateHandle())

        val first = launch { viewModel.sendDash(Dash.valueOf(100_000), false, null) }
        val second = launch { viewModel.sendDash(Dash.valueOf(100_000), false, null) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()

        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
        assertFalse(viewModel.paymentOutcomeUnknown.value)

        // The send was refused (a retryable failure): the user may try again.
        viewModel.sendDash(Dash.valueOf(100_000), false, null)
        coVerify(exactly = 2) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun sellDash_outcomeUnknown_isTerminal_neverFailureNorSuccess_andRefusesASecondCommit() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        val savedState = SavedStateHandle()
        val viewModel = previewViewModel(savedState)
        var failureEvents = 0
        viewModel.onFailure.observeForever { failureEvents++ }

        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertTrue(viewModel.paymentOutcomeUnknown.value)
        assertEquals(0, failureEvents)
        assertNull(viewModel.saleSent.value)

        // Confirm again on the same screen: refused before any commit or send.
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        coVerify(exactly = 1) { coinBaseRepository.commitSwapTrade(any()) }
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun sellDash_aRejectedSend_isAFailure_notASuccess() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } throws IllegalStateException("rejected")
        val viewModel = previewViewModel(SavedStateHandle())
        var failureEvents = 0
        viewModel.onFailure.observeForever { failureEvents++ }

        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertEquals(1, failureEvents)
        assertNull("nothing was sent, so the sale is not reported as done", viewModel.saleSent.value)
        assertFalse(viewModel.paymentOutcomeUnknown.value)
    }

    @Test
    fun sellDash_aSuccessfulSend_isReportedAsSuccess() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val viewModel = previewViewModel(SavedStateHandle())

        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertEquals(CoinbaseConversionPreviewViewModel.SaleSent("txid"), viewModel.saleSent.value)
    }

    @Test
    fun sellDash_aSuccessDismissedByTheLock_isKept_andTheSaleCannotBeCommittedAgain() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val viewModel = previewViewModel(SavedStateHandle())
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        // Taken and shown; the lock dismisses the dialog unanswered, which gives it back.
        val sent = requireNotNull(viewModel.saleSent.value)
        assertTrue(viewModel.consumeSaleSent(sent))
        viewModel.retainSaleSent(sent)
        assertEquals("shown again after the unlock", sent, viewModel.saleSent.value)

        // Confirm on the preview meanwhile: refused, nothing committed or sent again.
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()
        assertTrue(viewModel.consumeSaleSent(sent))
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()
        coVerify(exactly = 1) { coinBaseRepository.commitSwapTrade(any()) }
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun sellDash_theStateSavedAtTheConfirmTap_beforeTheSendStarts_alreadyRefusesOnRestore() = runTest {
        // Android may save the instance state while the commit and address requests run (the
        // app was backgrounded) and never again before the process dies.
        val held = CompletableDeferred<Unit>()
        coEvery { coinBaseRepository.commitSwapTrade(any()) } coAnswers {
            held.await()
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        }
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val savedState = SavedStateHandle()
        val committing = previewViewModel(savedState).commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01")
        runCurrent()
        val savedWhileCommitting = restoredFrom(savedState)

        // The send then goes out; the process dies with only that early state.
        held.complete(Unit)
        committing.join()
        assertTrue(previewViewModel(savedWhileCommitting).paymentOutcomeUnknown.value)
    }

    @Test
    fun sellDash_aCommitRefusedBeforeTheSend_sentNothing_andIsNoLongerInProgress() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns failure()
        val savedState = SavedStateHandle()
        val viewModel = previewViewModel(savedState)
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertFalse(previewViewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()
        coVerify(exactly = 2) { coinBaseRepository.commitSwapTrade(any()) }
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
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
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

    @Test
    fun sellDash_aSendInProgress_whenTheProcessDies_isAnUnknownOutcome_onRestore() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        val held = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            held.await()
            "txid"
        }
        val savedState = SavedStateHandle()
        val committing = previewViewModel(savedState).commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01")
        runCurrent()

        // The process is killed while the Dash is being sent.
        val restored = previewViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        held.complete(Unit)
        committing.join()
        coVerify(exactly = 1) { coinBaseRepository.commitSwapTrade(any()) }
        coVerify(exactly = 1) { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) }
    }

    @Test
    fun sellDash_aSentSale_staysMarked_untilItsSuccessIsAnswered() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        coEvery { sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any()) } returns "txid"
        val savedState = SavedStateHandle()
        val viewModel = previewViewModel(savedState)

        viewModel.commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        // Sent; the success dialog is pending or up (e.g. the screen is stopped) when the process
        // dies: the restored preview must not sell and send again.
        val restored = previewViewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)

        viewModel.acknowledgeSaleResult()
        assertFalse(previewViewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
    }

    @Test
    fun sellDash_aSendThatReturned_isNotUnknown_onRestore() = runTest {
        coEvery { coinBaseRepository.commitSwapTrade(any()) } returns
            ResponseResource.Success(SwapTradeUIModel(swapTradeId = "trade", inputCurrency = Constants.DASH_CURRENCY))
        coEvery { coinBaseRepository.createAddress() } returns ResponseResource.Success("XcoinbaseDepositAddress")
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } throws IllegalStateException("rejected")
        val savedState = SavedStateHandle()
        previewViewModel(savedState).commitSwapTrade("trade", Constants.DASH_CURRENCY, "0.01").join()

        assertFalse(previewViewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
    }

    @Test
    fun aBuyCommitInProgress_whenTheProcessDies_isNotAPaymentOutcome() = runTest {
        // A buy commits on Coinbase and sends no Dash from the wallet.
        val entered = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        every { walletDataProvider.freshReceiveAddressStringLive() } returns "XreceiveAddress"
        coEvery { coinBaseRepository.commitSwapTrade(any()) } coAnswers {
            entered.complete(Unit)
            held.await()
            failure()
        }
        val savedState = SavedStateHandle()
        val committing = previewViewModel(savedState).commitSwapTrade("trade", "USDC", "10")
        // The receive address is read off the main thread first.
        entered.await()

        assertFalse(previewViewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)

        held.complete(Unit)
        committing.join()
    }

    @Test
    fun loading_isFalse_onANewViewModel_soARestoredProgressDialogIsTakenDown() {
        assertEquals(false, previewViewModel(SavedStateHandle()).showLoading.value)
        assertEquals(false, transferViewModel(SavedStateHandle()).observeLoadingState.value)
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
