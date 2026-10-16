package de.schildbach.wallet.ui.username.request

import android.app.Application
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.service.platform.ContestedUsernameFees
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ConfirmUserNameDialogViewModelTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun feeRefresh_updatesAmountWithoutAnotherExchangeRateEmission() = runTest {
        ContestedUsernameFees.reset()
        val config = mockk<WalletUIConfig>()
        every { config.observe(WalletUIConfig.SELECTED_CURRENCY) } returns flowOf("USD")
        val rates = mockk<ExchangeRatesProvider>()
        every { rates.observeExchangeRate("USD") } returns flowOf(ExchangeRate("USD", "100"))
        val viewModel = ConfirmUserNameDialogViewModel(mockk(relaxed = true), rates, config)
        try {
            viewModel.isContestableUsername = true
            runCurrent()
            assertEquals("0.25", viewModel.uiState.value.amountStr)

            ContestedUsernameFees.updateProtocolVersion(14)
            runCurrent()
            assertEquals("0.15", viewModel.uiState.value.amountStr)

            viewModel.hasIdentity = true
            assertEquals("0.10", viewModel.uiState.value.amountStr)
        } finally {
            viewModel.viewModelScope.cancel()
            ContestedUsernameFees.reset()
        }
    }
}
