package org.dash.android.lightpayprot

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.timeout
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dash.android.lightpayprot.data.SimplifiedPaymentRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.*

class SimplifiedPaymentViewModelTest {

    //https://medium.com/@harmittaa/retrofit-2-6-0-with-koin-and-coroutines-testing-your-layers-42d2a71566f1

    private lateinit var viewModel: SimplifiedPaymentViewModel
    private lateinit var lightPaymentRepo: LightPaymentRepo
    private lateinit var paymentRequestObserver: Observer<Resource<SimplifiedPaymentRequest>>
    private val validUrl = "Helsinki"
    private val invalidLocation = "Helsinkii"
    private val successResource = Resource.success(SimplifiedPaymentRequest("dash", emptyList(), Date(), Date(), "memo", "url", "merchantData"));
    private val errorResource = Resource.error("Unauthorised", null)

    @Rule
    @JvmField
    val instantExecutorRule = InstantTaskExecutorRule()

    @ObsoleteCoroutinesApi
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @ExperimentalCoroutinesApi
    @ObsoleteCoroutinesApi
    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        lightPaymentRepo = mock()
        runBlocking {
            whenever(lightPaymentRepo.getPaymentRequest(validUrl)).thenReturn(successResource)
            whenever(lightPaymentRepo.getPaymentRequest(invalidLocation)).thenReturn(errorResource)
        }
        viewModel = SimplifiedPaymentViewModel()
        viewModel.lightPaymentRepo = lightPaymentRepo
        paymentRequestObserver = mock()
    }

    @ObsoleteCoroutinesApi
    @ExperimentalCoroutinesApi
    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    @Test
    fun `when getWeather is called with valid location, then observer is updated with success`() = runBlocking {
        viewModel.paymentRequest.observeForever(paymentRequestObserver)
        viewModel.getPaymentRequest(validUrl)
        delay(10)
        verify(lightPaymentRepo).getPaymentRequest(validUrl)
        verify(paymentRequestObserver, timeout(50)).onChanged(Resource.loading(null))
        verify(paymentRequestObserver, timeout(50)).onChanged(successResource)
    }

    @Test
    fun `when getWeather is called with invalid location, then observer is updated with failure`() = runBlocking {
        viewModel.paymentRequest.observeForever(paymentRequestObserver)
        viewModel.getPaymentRequest(invalidLocation)
        delay(10)
        verify(lightPaymentRepo).getPaymentRequest(invalidLocation)
        verify(paymentRequestObserver, timeout(50)).onChanged(Resource.loading(null))
        verify(paymentRequestObserver, timeout(50)).onChanged(errorResource)
    }
}