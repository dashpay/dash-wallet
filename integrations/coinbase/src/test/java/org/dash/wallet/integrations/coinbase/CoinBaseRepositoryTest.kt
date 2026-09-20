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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.integrations.coinbase.model.AccountsResponse
import org.dash.wallet.integrations.coinbase.model.Balance
import org.dash.wallet.integrations.coinbase.model.CoinbaseAccount
import org.dash.wallet.integrations.coinbase.model.SendTransactionToWalletParams
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepository
import org.dash.wallet.integrations.coinbase.service.CoinBaseAuthApi
import org.dash.wallet.integrations.coinbase.service.CoinBaseServicesApi
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.UUID

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

    private fun account(currency: String, name: String = "$currency Wallet") = CoinbaseAccount(
        uuid = UUID.randomUUID(),
        name = name,
        currency = currency,
        availableBalance = Balance("0", currency),
        default = false,
        active = true,
        type = "ACCOUNT_TYPE_CRYPTO",
        ready = true
    )

    private fun mockEmptyAccountCache() {
        coEvery { config.getAccounts() } returns emptyMap()
        coEvery { config.setAccounts(any()) } just Runs
    }

    @Test
    fun `getUserAccount follows the pagination cursor across pages`() {
        mockEmptyAccountCache()
        coEvery { coinBaseServicesApi.getAccounts(any(), null) } returns
            AccountsResponse(listOf(account("BTC")), hasNext = true, cursor = "cursor-1")
        coEvery { coinBaseServicesApi.getAccounts(any(), "cursor-1") } returns
            AccountsResponse(listOf(account("USDC")), hasNext = false, cursor = null)

        val usdcAccount = runBlocking { coinBaseRepository.getUserAccount("USDC") }

        assertThat(usdcAccount.currency, `is`("USDC"))
        coVerify(exactly = 1) { coinBaseServicesApi.getAccounts(any(), null) }
        coVerify(exactly = 1) { coinBaseServicesApi.getAccounts(any(), "cursor-1") }
    }

    @Test
    fun `getUserAccount throws when has_next comes without a cursor`() {
        mockEmptyAccountCache()
        coEvery { coinBaseServicesApi.getAccounts(any(), any()) } returns
            AccountsResponse(listOf(account("USDC")), hasNext = true, cursor = null)

        // Partial data must never be returned, even if the target currency is already in it:
        // a truncated list is indistinguishable from "no account" for every other currency.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { coinBaseRepository.getUserAccount("USDC") }
        }
    }

    @Test
    fun `getUserAccount throws when the page limit is exhausted with has_next still true`() {
        mockEmptyAccountCache()
        coEvery { coinBaseServicesApi.getAccounts(any(), any()) } answers {
            AccountsResponse(listOf(account("BTC")), hasNext = true, cursor = "cursor-loop")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coinBaseRepository.getUserAccount("USDC") }
        }
        // Bounded: the runaway guard stops the loop at 40 pages.
        coVerify(exactly = 40) { coinBaseServicesApi.getAccounts(any(), any()) }
    }
}
