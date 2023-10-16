/*
 * Copyright 2021 Dash Core Group.
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

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.integrations.coinbase.model.SendTransactionToWalletParams
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepository
import org.dash.wallet.integrations.coinbase.service.CoinBaseAuthApi
import org.dash.wallet.integrations.coinbase.service.CoinBaseServicesApi
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Test

class CoinBaseRepositoryTest {
    @MockK lateinit var coinBaseServicesApi: CoinBaseServicesApi
    @MockK lateinit var coinBaseAuthApi: CoinBaseAuthApi
    @MockK lateinit var config: CoinbaseConfig
    @MockK lateinit var swapTradeMapper: SwapTradeMapper
    @MockK lateinit var coinbaseAddressMapper: CoinbaseAddressMapper
    private lateinit var coinBaseRepository: CoinBaseRepository
    private val accountId = "423095d3-bb89-5cef-b1bc-d1dfe6e13857"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        coEvery { config.get(CoinbaseConfig.USER_ACCOUNT_ID) } returns accountId
        every { config.observe(CoinbaseConfig.LAST_ACCESS_TOKEN) } returns MutableStateFlow("access_token")

        coinBaseRepository = CoinBaseRepository(
            coinBaseServicesApi,
            coinBaseAuthApi,
            config,
            mockk(),
            swapTradeMapper,
            coinbaseAddressMapper,
            mockk()
        )
    }

    @Test
    fun `when fetching active payment methods repository return success with data`() {
        val expectedPaymentMethods = TestUtils.getPaymentMethodsApiResponse()
        coEvery { coinBaseServicesApi.getActivePaymentMethods() } returns expectedPaymentMethods
        val actualSuccessResponse = runBlocking { coinBaseRepository.getActivePaymentMethods() }
        coVerify { coinBaseServicesApi.getActivePaymentMethods() }
        assertThat(actualSuccessResponse, `is`(TestUtils.paymentMethodsData))
    }

    @Test
    fun `when sending funds to dash wallet, repository returns success response `() {
        val params = SendTransactionToWalletParams("0.5", "usd", "9316dd16-0c05", "XfVe4NAHTp6NwWuM3PGpmUSwuZuWWE9qY3", "send")
        val expectedSendFundsToWalletResponse = TestUtils.sendFundsToWalletApiResponse()
        coEvery { coinBaseServicesApi.sendCoinsToWallet(api2FATokenVersion ="2345",accountId = accountId, sendTransactionToWalletParams = params) } returns expectedSendFundsToWalletResponse

        runBlocking { coinBaseRepository.sendFundsToWallet(params,"2345") }
        coVerify { coinBaseServicesApi.sendCoinsToWallet(api2FATokenVersion = "2345",accountId = accountId, sendTransactionToWalletParams = params) }
    }
}
