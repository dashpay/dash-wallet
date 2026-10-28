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
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
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

    private fun viewModel(savedState: SavedStateHandle) = MayaConversionPreviewViewModel(
        swapProvider,
        mock(),
        mock(),
        mock(),
        networkState,
        mock(),
        mock(),
        savedState
    )

    private suspend fun stubOutcomeUnknown() {
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        whenever(swapProvider.commitSwapTransaction(any(), any())).thenReturn(
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

        verify(swapProvider, times(1)).commitSwapTransaction(any(), any())
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

        verify(swapProvider, times(1)).commitSwapTransaction(any(), any())
    }

    @Test
    fun concurrentConfirms_onlyOneCommits_andARetryableFailureAllowsAnother() = runTest {
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        val gate = CompletableDeferred<Unit>()
        whenever(swapProvider.commitSwapTransaction(any(), any())).doSuspendableAnswer {
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

        verify(swapProvider, times(1)).commitSwapTransaction(any(), any())
        assertFalse(viewModel.paymentOutcomeUnknown.value)

        // The commit failed (retryable): a later Confirm is accepted again.
        viewModel.commitSwapTrade("trade").join()
        verify(swapProvider, times(2)).commitSwapTransaction(any(), any())
    }
}
