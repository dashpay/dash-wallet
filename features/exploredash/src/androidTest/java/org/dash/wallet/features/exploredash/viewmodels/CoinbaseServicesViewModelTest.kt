package org.dash.wallet.integration.coinbase_integration.viewmodels
/*
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.integration.coinbase_integration.CoroutineDispatcherRule
import org.dash.wallet.integration.coinbase_integration.TestUtils
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountInfo
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.junit.Before
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import retrofit2.Response

//todo handle refactoring for fetching user account info before resuming work on this test class

@ExperimentalCoroutinesApi
class CoinbaseServicesViewModelTest {
    @MockK lateinit var coinBaseRepositoryInt: CoinBaseRepositoryInt
    @MockK lateinit var exchangeRatesProvider: ExchangeRatesProvider
    @MockK lateinit var configuration: Configuration
    @MockK lateinit var walletDataProvider: WalletDataProvider
    private lateinit var coinbaseServicesViewModel: CoinbaseServicesViewModel
    private val currencyCode = "USD"
    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutinesDispatcherRule = CoroutineDispatcherRule()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        coinbaseServicesViewModel = CoinbaseServicesViewModel(coinBaseRepositoryInt, exchangeRatesProvider, configuration, walletDataProvider)
        coEvery { configuration.exchangeCurrencyCode } returns currencyCode
    }


    @Test
    fun `when getPaymentMethods() is called, should load payment methods data`() = runBlocking {
        val expectedPaymentMethods = listOf(
            PaymentMethod("931aa7a2-6500-505b-bf0b-35f031466711", "Bank of America - Busi...", "********1234", "", PaymentMethodType.BankAccount),
            PaymentMethod("2e55fc67-db8d-5e14-81f6-25432dc27227", "PayPal", "t***t@fakemail.com", "", PaymentMethodType.PayPal),
        )
        coEvery { coinBaseRepositoryInt.getActivePaymentMethods() } returns ResponseResource.Success(TestUtils.paymentMethodsData!!)

        val responseBody = mockk<CoinBaseUserAccountInfo>()
        val mockResponse = Response.success(responseBody)

        coEvery { coinBaseRepositoryInt.getUserAccount() } returns ResponseResource.Success(mockResponse)

        coinbaseServicesViewModel.getPaymentMethods()
        assertThat(coinbaseServicesViewModel.activePaymentMethods.value, equalTo(expectedPaymentMethods))
    }
}

 */