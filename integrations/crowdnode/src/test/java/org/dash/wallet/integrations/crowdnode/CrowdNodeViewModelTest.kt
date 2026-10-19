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
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private val fullBalance = Dash.COIN.multiply(4)
    private val balance = fullBalance

    private val api = mock<CrowdNodeApi> {
        // Note: matchers like any() can't be used for Dash parameters (inline value class),
        // so the deposit stubs use the concrete amounts the tests pass.
        // (fullBalance, not balance: inside this lambda `balance` resolves to the mock's property.)
        onBlocking { deposit(fullBalance, emptyWallet = true, checkBalanceConditions = false) } doReturn true
        onBlocking {
            deposit(fullBalance.div(6), emptyWallet = false, checkBalanceConditions = false)
        } doReturn true
        on { signUpStatus } doReturn MutableStateFlow(SignUpStatus.Finished)
        on { onlineAccountStatus } doReturn MutableStateFlow(OnlineAccountStatus.None)
        on { apiError } doReturn MutableStateFlow(null)
        on { balance } doReturn MutableStateFlow(Resource.success(Dash.ZERO))
        doNothing().whenever(mock).refreshBalance()
    }

    private val walletData = mock<WalletDataProvider> {
        on { observeTotalBalance() } doReturn MutableStateFlow(fullBalance)
        on { freshReceiveAddressString() } doReturn "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U"
    }

    private val exchangeRatesMock = mock<ExchangeRatesProvider> {
        on { observeExchangeRate(any()) } doReturn flow { ExchangeRate("USD", "100") }
    }

    private val blockchainStateMock = mock<BlockchainStateProvider> {
        onBlocking { getMasternodeAPY() } doReturn 5.9
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
            val address = "yjMvPFucZWPZXKBaEDxHzZrm5Px44UhgJs"
            api.stub {
                on { accountAddress } doReturn address
            }

            viewModel.recheckState()

            verify(api).restoreStatus()
            assertEquals(address, viewModel.accountAddress.value)
        }
    }

    /**
     * The address read behind [CrowdNodeViewModel.recheckState], parked on
     * demand so the failure can be made to arrive after cancellation — the way
     * the real one does, since it waits for the SDK engine on an uncancellable
     * sleep.
     */
    private class ParkedAddressRead {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun read(): String {
            reached.countDown()
            assertTrue("the address read was never released", release.await(10L, TimeUnit.SECONDS))
            throw ReceiveAddressUnavailableException()
        }
    }

    private fun viewModelWith(api: CrowdNodeApi, wallet: WalletDataProvider) = CrowdNodeViewModel(
        mock(), mock(), wallet, api, mock(),
        exchangeRatesMock, mock(), blockchainStateMock, mock(), mock()
    )

    @Test
    fun recheckState_addressUnavailable_reportsTheError() {
        val errors = MutableStateFlow<Exception?>(null)
        api.stub {
            onBlocking { restoreStatus() } doReturn Unit
            on { accountAddress } doReturn null
            on { apiError } doReturn errors
        }
        val wallet = mock<WalletDataProvider> {
            on { observeTotalBalance() } doReturn MutableStateFlow(fullBalance)
            on { freshReceiveAddressStringLive() } doAnswer { throw ReceiveAddressUnavailableException() }
        }

        runBlocking { viewModelWith(api, wallet).recheckState() }

        // StakingActivity observes this for the whole staking flow, so it is the
        // only thing that puts the failure in front of the user.
        assertTrue(
            "an unavailable address must be reported",
            errors.value is ReceiveAddressUnavailableException
        )
    }

    @Test
    fun recheckState_cancelledBeforeTheAddressReadFails_reportsNothing() {
        val errors = MutableStateFlow<Exception?>(null)
        val parked = ParkedAddressRead()
        api.stub {
            onBlocking { restoreStatus() } doReturn Unit
            on { accountAddress } doReturn null
            on { apiError } doReturn errors
        }
        val wallet = mock<WalletDataProvider> {
            on { observeTotalBalance() } doReturn MutableStateFlow(fullBalance)
            on { freshReceiveAddressStringLive() } doAnswer { parked.read() }
        }
        val viewModel = viewModelWith(api, wallet)

        // StakingActivity calls this from its own lifecycleScope, so leaving
        // staking cancels it — but the read cannot answer that cancellation, and
        // a `withContext` whose body throws delivers the throw rather than the
        // cancellation, so the handler still runs.
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val job = scope.launch { viewModel.recheckState() }
        assertTrue("the read must have parked", parked.reached.await(10L, TimeUnit.SECONDS))
        job.cancel()
        parked.release.countDown()
        runBlocking { job.join() }

        // apiError is a singleton the staking flow shares, and only a dialog
        // actually shown clears it: latching here greets the NEXT staking
        // session with a stale failure before anything has been read.
        assertNull(
            "a read abandoned with the screen must not latch an app-scoped error",
            errors.value
        )
    }
}
