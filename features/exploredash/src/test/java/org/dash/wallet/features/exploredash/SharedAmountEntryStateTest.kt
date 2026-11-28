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
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.leaveGiftCardPurchase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The amount entry's view model belongs to the activity, so Receive and the other screens get
 * whatever the gift card screen left in it.
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
    fun leavingTheGiftCardPurchase_handsTheEntryBackClean() {
        val entry = entry()
        entry.setMinAmount(Coin.ZERO, true)
        var canContinue: Boolean? = null
        entry.canContinue.observeForever { canContinue = it }
        // what the purchase screen does to the entry while it is open
        entry.selectedCurrencyCode = "KWD"
        entry.blockContinue = true
        entry.fiatDecimalsLimit = 0
        assertEquals(false, canContinue)

        entry.leaveGiftCardPurchase()

        assertEquals(null, entry.fiatDecimalsLimit)

        assertEquals("Receive must be able to continue", true, canContinue)
        assertEquals("EUR", entry.selectedCurrencyCode)
        assertEquals(Fiat.valueOf("KWD", 0), entry.fiatAmount.value)
    }
}
