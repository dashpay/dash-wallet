package de.schildbach.wallet.ui.more

import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SecurityViewModelTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val walletUIConfig = mockk<WalletUIConfig>()
    private val walletData = mockk<WalletDataProvider>(relaxed = true)
    private val walletApplication = mockk<WalletApplication>(relaxed = true)

    @Before
    fun setUp() {
        every { walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE) } returns flowOf(false)
        coEvery { walletUIConfig.set(WalletUIConfig.AUTO_HIDE_BALANCE, any()) } just runs
        mockkStatic(WalletBalanceWidgetProvider::class)
        every { WalletBalanceWidgetProvider.updateWidgets(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkStatic(WalletBalanceWidgetProvider::class)
    }

    @Test
    fun `setting autohide refreshes widget when wallet is present`() = runTest {
        every { walletApplication.hasWallet() } returns true
        every { walletData.getWalletBalance() } returns Coin.COIN

        val viewModel = createViewModel()
        viewModel.setHideBalanceOnLaunch(true)
        // The refresh runs on Dispatchers.IO, outside runTest's scheduler.
        viewModel.viewModelScope.coroutineContext.job.children.toList().joinAll()

        coVerify { walletUIConfig.set(WalletUIConfig.AUTO_HIDE_BALANCE, true) }
        verify(timeout = 1_000) { WalletBalanceWidgetProvider.updateWidgets(walletApplication, Coin.COIN) }
    }

    @Test
    fun `setting autohide does not refresh widget when wallet is absent`() = runTest {
        every { walletApplication.hasWallet() } returns false

        val viewModel = createViewModel()
        viewModel.setHideBalanceOnLaunch(true)
        // Wait for the null-wallet branch before asserting that no refresh occurred.
        viewModel.viewModelScope.coroutineContext.job.children.toList().joinAll()

        coVerify { walletUIConfig.set(WalletUIConfig.AUTO_HIDE_BALANCE, true) }
        verify(exactly = 0) { WalletBalanceWidgetProvider.updateWidgets(any(), any()) }
    }

    private fun createViewModel() = SecurityViewModel(
        exchangeRates = mockk<ExchangeRatesProvider>(relaxed = true),
        configuration = mockk<Configuration>(relaxed = true),
        walletUIConfig = walletUIConfig,
        walletData = walletData,
        analytics = mockk<AnalyticsService>(relaxed = true),
        walletApplication = walletApplication,
        biometricHelper = mockk<BiometricHelper>(relaxed = true),
        blockchainIdentityConfig = mockk<BlockchainIdentityConfig> {
            every { observe() } returns emptyFlow()
        },
        dashPayProfileDao = mockk<DashPayProfileDao>(relaxed = true),
        platformSyncService = mockk<PlatformSyncService>(relaxed = true)
    )
}
