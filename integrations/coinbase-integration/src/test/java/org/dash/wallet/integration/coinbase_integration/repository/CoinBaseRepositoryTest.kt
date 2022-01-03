package org.dash.wallet.integration.coinbase_integration.repository

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.CommitBuyOrderMapper
import org.dash.wallet.integration.coinbase_integration.PlaceBuyOrderMapper
import org.dash.wallet.integration.coinbase_integration.SendFundsToWalletMapper
import org.dash.wallet.integration.coinbase_integration.TestUtils
import org.dash.wallet.integration.coinbase_integration.model.PlaceBuyOrderParams
import org.dash.wallet.integration.coinbase_integration.model.PlaceBuyOrderUIModel
import org.dash.wallet.integration.coinbase_integration.model.SendTransactionToWalletParams
import org.dash.wallet.integration.coinbase_integration.model.SendTransactionToWalletUIModel
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Test


class CoinBaseRepositoryTest {
    @MockK lateinit var coinBaseServicesApi: CoinBaseServicesApi
    @MockK lateinit var coinBaseAuthApi: CoinBaseAuthApi
    @MockK lateinit var configuration: Configuration
    @MockK lateinit var placeBuyOrderMapper: PlaceBuyOrderMapper
    @MockK lateinit var commitBuyOrderMapper: CommitBuyOrderMapper
    @MockK lateinit var sendFundsToWalletMapper: SendFundsToWalletMapper
    private lateinit var coinBaseRepository: CoinBaseRepository
    private val accountId = "423095d3-bb89-5cef-b1bc-d1dfe6e13857"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        coinBaseRepository = CoinBaseRepository(
            coinBaseServicesApi,
            coinBaseAuthApi,
            configuration,
            placeBuyOrderMapper,
            commitBuyOrderMapper,
            sendFundsToWalletMapper
        )
        coEvery { configuration.coinbaseUserAccountId } returns accountId
    }

    @Test
    fun `when fetching active payment methods repository return success with data`() {
        val expectedPaymentMethods = TestUtils.getPaymentMethodsApiResponse()
        coEvery { coinBaseServicesApi.getActivePaymentMethods() } returns expectedPaymentMethods
        val actualSuccessResponse = runBlocking { coinBaseRepository.getActivePaymentMethods() }
        coVerify { coinBaseServicesApi.getActivePaymentMethods() }
        assertThat(actualSuccessResponse, `is`(ResponseResource.Success(TestUtils.paymentMethodsData)))
    }

    @Test
    fun `when placing a buy order, repository returns success with data`(){
        val expectedPlaceBuyOrderResponse = TestUtils.placeBuyOrderApiResponse()
        val expectedPlaceBuyOrderUIModel = PlaceBuyOrderUIModel(
            "5ccb6a4a-6296-5ca6-8fb5-8e66740925ef",
        "931aa7a2-6500-505b-bf0b-35f031466711",
        "0.99",
        "usd")
        val expectedPlaceBuyOrderData = TestUtils.buyOrderData
        val accountId = "423095d3-bb89-5cef-b1bc-d1dfe6e13857"
        val params = PlaceBuyOrderParams("0.5", "usd", "931aa7a2-6500-505b-bf0b-35f031466711",
            commit = true,
            quote = false
        )
        coEvery { coinBaseServicesApi.placeBuyOrder(accountId = accountId, placeBuyOrderParams = params) } returns expectedPlaceBuyOrderResponse
        coEvery { placeBuyOrderMapper.map(expectedPlaceBuyOrderData) } returns expectedPlaceBuyOrderUIModel
        val actualSuccessResponse = runBlocking { coinBaseRepository.placeBuyOrder(params) }
        assertThat(actualSuccessResponse, `is`(ResponseResource.Success(expectedPlaceBuyOrderUIModel)))
    }

    @Test
    fun `when sending funds to dash wallet, repository returns success with data`(){
        val params = SendTransactionToWalletParams("0.5", "usd", "9316dd16-0c05", "XfVe4NAHTp6NwWuM3PGpmUSwuZuWWE9qY3", "send")
        val expectedSendFundsToWalletResponse = TestUtils.sendFundsToWalletApiResponse()
        val expectedSendFundsToWalletData = TestUtils.sendFundsToWalletData
        val expectedSendFundsToWalletUIModel = SendTransactionToWalletUIModel("completed")

        coEvery { coinBaseServicesApi.sendCoinsToWallet(accountId = accountId, sendTransactionToWalletParams = params) } returns expectedSendFundsToWalletResponse
        coEvery { sendFundsToWalletMapper.map(expectedSendFundsToWalletData) } returns expectedSendFundsToWalletUIModel

        val actualSuccessResponse = runBlocking { coinBaseRepository.sendFundsToWallet(params) }
        assertThat(actualSuccessResponse, `is`(ResponseResource.Success(expectedSendFundsToWalletUIModel)))
    }
}