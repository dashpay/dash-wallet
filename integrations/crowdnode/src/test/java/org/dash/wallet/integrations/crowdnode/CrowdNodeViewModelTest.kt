/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.api.SignUpStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.kotlin.*

@ExperimentalCoroutinesApi
class MainCoroutineRule(
    private val dispatcher: TestCoroutineDispatcher = TestCoroutineDispatcher()
) : TestWatcher(), TestCoroutineScope by TestCoroutineScope(dispatcher) {
    override fun starting(description: Description?) {
        super.starting(description)
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description?) {
        super.finished(description)
        cleanupTestCoroutines()
        Dispatchers.resetMain()
    }
}

@ExperimentalCoroutinesApi
class CrowdNodeViewModelTest {
    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val balance = Coin.COIN.multiply(4)

    private val api = mock<CrowdNodeApi> {
        onBlocking { deposit(any(), any()) } doReturn true
        on { signUpStatus } doReturn MutableStateFlow(SignUpStatus.Finished)
        on { apiError } doReturn MutableStateFlow(null)
        on { balance } doReturn MutableStateFlow(Resource.success(Coin.ZERO))
        doNothing().whenever(mock).refreshBalance()
    }

    private val globalConfig = mock<Configuration> {
        on { exchangeCurrencyCode } doReturn "USD"
    }

    private val localConfig = mock<CrowdNodeConfig> {
        onBlocking { getPreference<String>(any()) } doReturn "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi"
    }

    private val walletData = mock<WalletDataProvider> {
        on { observeBalance() } doReturn MutableStateFlow(balance)
    }

    private val exchangeRatesMock = mock<ExchangeRatesProvider> {
        on { observeExchangeRate(any()) } doReturn flow { ExchangeRate("USD", "100") }
    }

    @Test
    fun deposit_fullBalance_setsEmptyWallet() {
        runBlocking {
            val viewModel = CrowdNodeViewModel(globalConfig, localConfig, walletData, api, exchangeRatesMock)
            viewModel.deposit(balance)
            verify(api).deposit(balance, true)
        }
    }

    @Test
    fun deposit_lessThanFullBalance_doesNotSetEmptyWallet() {
        runBlocking {
            val partial = balance.div(6)
            val viewModel = CrowdNodeViewModel(globalConfig, localConfig, walletData, api, exchangeRatesMock)
            viewModel.deposit(partial)
            verify(api).deposit(partial, false)
        }
    }
}