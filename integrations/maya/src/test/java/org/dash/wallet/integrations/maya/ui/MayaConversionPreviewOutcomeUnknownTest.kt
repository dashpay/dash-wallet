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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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

    @Test
    fun commitOutcomeUnknown_raisesOutcomeUnknown_neverAConversionError() = runTest {
        val order = SwapTradeUIModel(Amount(), false, "vaultAddress", "destinationAddress")
        val swapProvider = mock<SwapProvider>()
        whenever(swapProvider.commitSwapTransaction(any(), any())).thenReturn(
            ResponseResource.Failure(
                PaymentSubmissionPendingException("unknown", IllegalStateException("timeout")),
                false,
                0,
                null
            )
        )
        val networkState = mock<NetworkStateInt>()
        whenever(networkState.isConnected).thenReturn(MutableStateFlow(true))
        val viewModel = MayaConversionPreviewViewModel(
            swapProvider,
            mock(),
            mock(),
            mock(),
            networkState,
            mock(),
            mock(),
            SavedStateHandle(mapOf("swap_trade_order" to order))
        )
        var outcomeUnknownEvents = 0
        var failureEvents = 0
        viewModel.onPaymentOutcomeUnknown.observeForever { outcomeUnknownEvents++ }
        viewModel.commitSwapTradeFailureState.observeForever { failureEvents++ }

        viewModel.commitSwapTrade("trade").join()

        assertEquals(1, outcomeUnknownEvents)
        assertEquals(0, failureEvents)
    }
}
