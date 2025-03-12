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
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.kotlin.*

@ExperimentalCoroutinesApi
class MainCoroutineRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
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
        onBlocking { deposit(any(), any(), any()) } doReturn true
        on { signUpStatus } doReturn MutableStateFlow(SignUpStatus.Finished)
        on { onlineAccountStatus } doReturn MutableStateFlow(OnlineAccountStatus.None)
        on { apiError } doReturn MutableStateFlow(null)
        on { balance } doReturn MutableStateFlow(Resource.success(Coin.ZERO))
        doNothing().whenever(mock).refreshBalance()
    }

    private val walletData = mock<WalletDataProvider> {
        on { observeSpendableBalance() } doReturn MutableStateFlow(balance)
        on {
            freshReceiveAddress()
        } doReturn Address.fromBase58(TestNet3Params.get(), "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U")
    }

    private val exchangeRatesMock = mock<ExchangeRatesProvider> {
        on { observeExchangeRate(any()) } doReturn flow { ExchangeRate("USD", "100") }
    }

    private val blockchainStateMock = mock<BlockchainStateProvider> {
        on { getMasternodeAPY() } doReturn 5.9
    }

    @Test
    fun deposit_fullBalance_setsEmptyWallet() {
        runBlocking {
            val viewModel = CrowdNodeViewModel(
                mock(), mock(), walletData, api,
                mock(), exchangeRatesMock, mock(), blockchainStateMock, mock(), mock()
            )
            viewModel.deposit(balance, false)
            verify(api).deposit(balance, emptyWallet = true, checkBalanceConditions = false)
        }
    }

    @Test
    fun deposit_lessThanFullBalance_doesNotSetEmptyWallet() {
        runBlocking {
            val partial = balance.div(6)
            val viewModel = CrowdNodeViewModel(
                mock(), mock(), walletData, api,
                mock(), exchangeRatesMock, mock(), blockchainStateMock, mock(), mock()
            )
            viewModel.deposit(partial, false)
            verify(api).deposit(partial, emptyWallet = false, checkBalanceConditions = false)
        }
    }

    @Test
    fun recheckState_accountAddressIsSame() {
        runBlocking {
            api.stub {
                onBlocking { restoreStatus() } doReturn Unit
                on { accountAddress } doReturn null
            }
            val viewModel = CrowdNodeViewModel(
                mock(), mock(), walletData, api, mock(),
                exchangeRatesMock, mock(), blockchainStateMock, mock(), mock()
            )
            val address = Address.fromBase58(TestNet3Params.get(), "yjMvPFucZWPZXKBaEDxHzZrm5Px44UhgJs")
            api.stub {
                on { accountAddress } doReturn address
            }

            viewModel.recheckState()

            verify(api).restoreStatus()
            assertEquals(address, viewModel.accountAddress.value)
        }
    }
}
