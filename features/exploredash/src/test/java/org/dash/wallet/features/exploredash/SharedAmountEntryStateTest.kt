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

package org.dash.wallet.features.exploredash

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The amount entry's view model belongs to the activity, so Receive and the other screens get
 * whatever the gift card screen left in it. These are the two calls that screen makes on its way out.
 */
@ExperimentalCoroutinesApi
class SharedAmountEntryStateTest {
    @get:Rule var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule var mainCoroutineRule = MainCoroutineRule()

    private fun entry(): EnterAmountViewModel {
        val walletUIConfig = mock<WalletUIConfig> {
            on { observe<String>(any()) } doReturn emptyFlow()
            onBlocking { getExchangeCurrencyCode() } doReturn "EUR"
        }
        val exchangeRates = mock<ExchangeRatesProvider> { on { observeExchangeRate(any()) } doReturn emptyFlow() }
        return EnterAmountViewModel(SavedStateHandle(), exchangeRates, walletUIConfig)
    }

    @Test
    fun releasingTheBlock_letsTheNextScreenContinue() {
        val entry = entry()
        entry.setMinAmount(Coin.ZERO, true)
        var canContinue: Boolean? = null
        entry.canContinue.observeForever { canContinue = it }

        entry.blockContinue = true
        assertEquals(false, canContinue)

        entry.blockContinue = false
        assertEquals(true, canContinue)
    }

    @Test
    fun resettingTheCurrency_goesBackToTheWalletCurrency() {
        val entry = entry()
        entry.selectedCurrencyCode = "KWD"

        entry.resetCurrency()

        assertEquals("EUR", entry.selectedCurrencyCode)
    }
}
