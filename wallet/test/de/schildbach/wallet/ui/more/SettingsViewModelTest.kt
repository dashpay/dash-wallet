/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.more

import android.os.PowerManager
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the (production) [SettingsViewModel] wiring that
 * survives the migration-debug cleanup: the UI state mirrors the current
 * battery-optimization status and the selected local currency, and the
 * transaction-metadata subtitle setter updates state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val powerManager = mockk<PowerManager> {
        every { isIgnoringBatteryOptimizations(any()) } returns true
    }
    private val walletApplication = mockk<WalletApplication> {
        every { getSystemService(PowerManager::class.java) } returns powerManager
        every { packageName } returns "hashengineering.darkcoin.wallet_test"
    }
    private val walletUIConfig = mockk<WalletUIConfig> {
        every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns flowOf("USD")
    }
    private val dashPayConfig = mockk<DashPayConfig>()
    private val blockchainIdentityConfig = mockk<BlockchainIdentityConfig> {
        every { observe() } returns emptyFlow()
        every { observeBase() } returns emptyFlow()
    }
    private val dashPayProfileDao = mockk<DashPayProfileDao>()
    private val walletDataProvider = mockk<WalletData>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        analytics: AnalyticsService = mockk<AnalyticsService>(relaxed = true)
    ) = SettingsViewModel(
        walletApplication = walletApplication,
        walletUIConfig = walletUIConfig,
        walletDataProvider = walletDataProvider,
        analytics = analytics,
        configuration = mockk<Configuration>(),
        dashPayConfig = dashPayConfig,
        blockchainIdentityConfig = blockchainIdentityConfig,
        blockchainServiceConfig = mockk<BlockchainServiceConfig>(),
        dashPayProfileDao = dashPayProfileDao
    )

    @Test
    fun initialState_reflectsBatteryOptimizationStatus() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.ignoringBatteryOptimizations)
    }

    @Test
    fun selectedCurrency_isReflectedInState() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertEquals("USD", viewModel.uiState.value.localCurrencySymbol)
    }

    @Test
    fun updateTransactionMetadataSubtitle_updatesState() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.updateTransactionMetadataSubtitle("Last saved: Jan 15, 2024")

        assertEquals("Last saved: Jan 15, 2024", viewModel.uiState.value.transactionMetadataSubtitle)
    }

    private fun analyticsService(enabled: Boolean, available: Boolean): AnalyticsService {
        var stored = enabled
        return mockk(relaxed = true) {
            every { isEnabled } answers { stored }
            every { isEnabled = any() } answers { stored = firstArg() }
            every { isAvailable } returns available
        }
    }

    @Test
    fun analyticsState_enabled_isInitializedFromService() = runTest(dispatcher) {
        val state = viewModel(analyticsService(enabled = true, available = true)).uiState.value

        assertTrue(state.analyticsEnabled)
        assertTrue(state.analyticsAvailable)
    }

    @Test
    fun analyticsState_disabled_isInitializedFromService() = runTest(dispatcher) {
        val state = viewModel(analyticsService(enabled = false, available = true)).uiState.value

        assertFalse(state.analyticsEnabled)
        assertTrue(state.analyticsAvailable)
    }

    @Test
    fun analyticsState_unavailable_isInitializedFromService() = runTest(dispatcher) {
        val state = viewModel(analyticsService(enabled = true, available = false)).uiState.value

        assertFalse(state.analyticsAvailable)
    }

    @Test
    fun setAnalyticsEnabled_false_updatesServiceAndState() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = true, available = true)
        val viewModel = viewModel(analytics)

        viewModel.setAnalyticsEnabled(false)

        verify { analytics.isEnabled = false }
        assertFalse(viewModel.uiState.value.analyticsEnabled)
    }

    @Test
    fun setAnalyticsEnabled_true_updatesServiceAndState() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = false, available = true)
        val viewModel = viewModel(analytics)

        viewModel.setAnalyticsEnabled(true)

        verify { analytics.isEnabled = true }
        assertTrue(viewModel.uiState.value.analyticsEnabled)
    }
}
