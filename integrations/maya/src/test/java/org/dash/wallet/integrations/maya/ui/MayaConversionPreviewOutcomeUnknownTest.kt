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

package org.dash.wallet.integrations.maya.ui

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.integrations.maya.api.DispatchingSwapProvider
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.maya.utils.SwapBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A swap deposit whose outcome is unknown ([PaymentSubmissionPendingException], e.g. an ambiguous
 * SDK send on the SwapKit deposit route) may already be on the network. The preview screen must
 * report it as such, never as a conversion error, whose dialog offers a retry that can pay twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MayaConversionPreviewOutcomeUnknownTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val swapProvider = mock<SwapProvider>()
    private val networkState = mock<NetworkStateInt>()
    private val order = SwapTradeUIModel(Amount(), false, "vaultAddress", "destinationAddress")

    private val walletDataProvider = mock<WalletDataProvider>()
    private val dispatchingSwapProvider = mock<DispatchingSwapProvider>()

    private fun viewModel(savedState: SavedStateHandle) = MayaConversionPreviewViewModel(
        swapProvider,
        dispatchingSwapProvider,
        walletDataProvider,
        mock(),
        networkState,
        mock(),
        mock(),
        savedState
    )

    private suspend fun stubOutcomeUnknown() {
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        whenever(swapProvider.commitSwapTransaction(any(), any(), any())).thenReturn(
            ResponseResource.Failure(
                PaymentSubmissionPendingException("unknown", IllegalStateException("timeout")),
                false,
                0,
                null
            )
        )
    }

    @Test
    fun commitOutcomeUnknown_isTerminal_neverAConversionError_andRefusesASecondCommit() = runTest {
        stubOutcomeUnknown()
        val viewModel = viewModel(SavedStateHandle(mapOf("swap_trade_order" to order)))
        var failureEvents = 0
        viewModel.commitSwapTradeFailureState.observeForever { failureEvents++ }

        viewModel.commitSwapTrade("trade").join()

        assertTrue(viewModel.paymentOutcomeUnknown.value)
        assertEquals(0, failureEvents)

        // Confirm again on the same screen: refused before anything is committed or sent.
        viewModel.commitSwapTrade("trade").join()

        verify(swapProvider, times(1)).commitSwapTransaction(any(), any(), any())
    }

    @Test
    fun commitOutcomeUnknown_survivesRestore_andStillRefusesACommit() = runTest {
        stubOutcomeUnknown()
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        viewModel(savedState).commitSwapTrade("trade").join()

        // A new view model over the saved state, as after the process was killed and restored.
        val restored = viewModel(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) }))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.commitSwapTrade("trade").join()

        verify(swapProvider, times(1)).commitSwapTransaction(any(), any(), any())
    }

    @Test
    fun concurrentConfirms_onlyOneCommits_andARetryableFailureAllowsAnother() = runTest {
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        val gate = CompletableDeferred<Unit>()
        whenever(swapProvider.commitSwapTransaction(any(), any(), any())).doSuspendableAnswer {
            gate.await()
            ResponseResource.Failure(IllegalStateException("route refused"), false, 0, null)
        }
        val viewModel = viewModel(SavedStateHandle(mapOf("swap_trade_order" to order)))

        // Two rapid Confirm taps: the first is still committing when the second arrives.
        val first = viewModel.commitSwapTrade("trade")
        val second = viewModel.commitSwapTrade("trade")
        gate.complete(Unit)
        first.join()
        second.join()

        verify(swapProvider, times(1)).commitSwapTransaction(any(), any(), any())
        assertFalse(viewModel.paymentOutcomeUnknown.value)

        // The commit failed (retryable): a later Confirm is accepted again.
        viewModel.commitSwapTrade("trade").join()
        verify(swapProvider, times(2)).commitSwapTransaction(any(), any(), any())
    }

    private fun restoredFrom(savedState: SavedStateHandle) =
        SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })

    /** The commit is held; when [deposit] is true it has passed the quote refresh and is depositing. */
    private suspend fun stubHeldCommit(gate: CompletableDeferred<Unit>, deposit: Boolean) {
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        whenever(swapProvider.commitSwapTransaction(any(), any(), any())).doSuspendableAnswer {
            if (deposit) {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[2] as suspend () -> Unit).invoke()
            }
            gate.await()
            ResponseResource.Failure(IllegalStateException("route refused"), false, 0, null)
        }
    }

    @Test
    fun aDepositInProgress_whenTheProcessDies_isAnUnknownOutcome_onRestore() = runTest {
        val gate = CompletableDeferred<Unit>()
        stubHeldCommit(gate, deposit = true)
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        val committing = viewModel(savedState).commitSwapTrade("trade")

        // The process is killed now; a new view model starts from the state saved meanwhile.
        val restored = viewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.commitSwapTrade("trade").join()

        gate.complete(Unit)
        committing.join()
        verify(swapProvider, times(1)).commitSwapTransaction(any(), any(), any())
    }

    @Test
    fun theStateSavedAtTheConfirmTap_beforeTheDepositStarts_alreadyRefusesOnRestore() = runTest {
        // Android may save the instance state while the commit still refreshes its quote (the
        // app was backgrounded) and never again before the process dies.
        val gate = CompletableDeferred<Unit>()
        stubHeldCommit(gate, deposit = false)
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        val committing = viewModel(savedState).commitSwapTrade("trade")
        val savedWhileRefreshing = restoredFrom(savedState)

        // The deposit may start and go out after that; the process dies with only that state.
        val restored = viewModel(savedWhileRefreshing)
        assertTrue(restored.paymentOutcomeUnknown.value)

        gate.complete(Unit)
        committing.join()
    }

    @Test
    fun aQuoteRefreshThatFails_sentNothing_andIsNoLongerInProgress() = runTest {
        // The commit stops before the deposit starts: nothing can have been sent.
        val gate = CompletableDeferred<Unit>()
        gate.complete(Unit)
        stubHeldCommit(gate, deposit = false)
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        val viewModel = viewModel(savedState)
        viewModel.commitSwapTrade("trade").join()

        assertFalse(viewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
        // And Confirm works again.
        viewModel.commitSwapTrade("trade").join()
        verify(swapProvider, times(2)).commitSwapTransaction(any(), any(), any())
    }

    @Test
    fun aDepositThatReturned_isNoLongerInProgress_onRestore() = runTest {
        val gate = CompletableDeferred<Unit>()
        gate.complete(Unit)
        stubHeldCommit(gate, deposit = true)
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        viewModel(savedState).commitSwapTrade("trade").join()

        // The deposit was refused (a retryable failure): the restored screen may confirm again.
        assertFalse(viewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
    }

    /** A deposit that went out (txid [sentTxid]); its IS-lock wait is held on [lock]. */
    private suspend fun stubSentDeposit(lock: CompletableDeferred<Unit>) {
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        whenever(dispatchingSwapProvider.currentBackend()).thenReturn(SwapBackend.MAYA)
        whenever(walletDataProvider.waitUntilLocked(any())).doSuspendableAnswer { lock.await() }
        whenever(swapProvider.commitSwapTransaction(any(), any(), any())).doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[2] as suspend () -> Unit).invoke()
            ResponseResource.Success(order.copy().apply { txid = sentTxid })
        }
    }

    private val sentTxid = "ab".repeat(32)

    @Test
    fun aSentDeposit_waitingForItsLock_whenTheProcessDies_isAnUnknownOutcome_onRestore() = runTest {
        val lock = CompletableDeferred<Unit>()
        stubSentDeposit(lock)
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        val committing = viewModel(savedState).commitSwapTrade("trade")

        // Broadcast; the process is killed while the screen waits up to 10s for the IS-lock.
        val restored = viewModel(restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        restored.commitSwapTrade("trade").join()

        lock.complete(Unit)
        committing.join()
        verify(swapProvider, times(1)).commitSwapTransaction(any(), any(), any())
    }

    @Test
    fun aSentDeposit_staysMarked_untilItsResultIsTakenOver() = runTest {
        val lock = CompletableDeferred<Unit>()
        lock.complete(Unit)
        stubSentDeposit(lock)
        val savedState = SavedStateHandle(mapOf("swap_trade_order" to order))
        val viewModel = viewModel(savedState)
        var delivered: SendTransactionToWalletParams? = null
        viewModel.commitSwapTradeSuccessState.observeForever { delivered = it }

        viewModel.commitSwapTrade("trade").join()
        assertEquals(sentTxid, delivered?.txid)

        // Delivered, but not yet recorded as the flow's pending result.
        assertTrue(viewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)

        viewModel.acknowledgeDepositResult()
        assertFalse(viewModel(restoredFrom(savedState)).paymentOutcomeUnknown.value)
    }
}
