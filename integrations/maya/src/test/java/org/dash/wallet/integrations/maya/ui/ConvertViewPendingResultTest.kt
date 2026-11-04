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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.ui.convert_currency.ConvertViewViewModel
import org.dash.wallet.integrations.maya.ui.convert_currency.model.MayaTransactionParams
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The pending conversion result the preview presents is observed as a flow that must stay live. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConvertViewPendingResultTest {
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

    private fun result(txid: String) = MayaTransactionParams(
        SendTransactionToWalletParams(Amount(), Amount(), "to", "send", txid = txid),
        TransactionType.SellSwap
    )

    private fun viewModel(): ConvertViewViewModel {
        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        every { walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        return ConvertViewViewModel(
            mockk(relaxed = true),
            walletUIConfig,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            SavedStateHandle()
        )
    }

    @Test
    fun afterTheFlowIsCleared_theNextConversionHasNoPendingResult_andALaterOneIsSeen() {
        val viewModel = viewModel()
        val results = viewModel.pendingConversionResults

        viewModel.pendingConversionResult = result("first")
        assertEquals(result("first"), results.value)

        // The user closes the successful conversion.
        viewModel.clearSavedState()
        assertNull("the next conversion must not present the previous success", results.value)

        viewModel.pendingConversionResult = result("second")
        assertEquals("a later result still reaches the observer", result("second"), results.value)
    }
}
