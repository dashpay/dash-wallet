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
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
    ).apply { ioDispatcher = dispatcher }

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

    private fun analyticsService(enabled: Boolean, available: Boolean, saves: Boolean = true): AnalyticsService {
        var stored = enabled
        return mockk(relaxed = true) {
            every { isEnabled } answers { stored }
            every { setEnabled(any()) } answers {
                // Mirrors the service: a failed save leaves the saved value in place.
                if (saves) stored = firstArg()
                saves
            }
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

    /** Collects every save-failure event the ViewModel emits for the rest of the test. */
    private fun TestScope.failuresOf(viewModel: SettingsViewModel): List<AnalyticsSaveFailure> {
        val events = mutableListOf<AnalyticsSaveFailure>()
        backgroundScope.launch { viewModel.analyticsSaveFailures.toList(events) }
        return events
    }

    @Test
    fun setAnalyticsEnabled_false_updatesServiceAndState() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = true, available = true)
        val viewModel = viewModel(analytics)
        val failures = failuresOf(viewModel)

        viewModel.setAnalyticsEnabled(false)

        verify { analytics.setEnabled(false) }
        assertFalse(viewModel.uiState.value.analyticsEnabled)
        assertEquals(emptyList<AnalyticsSaveFailure>(), failures)
    }

    @Test
    fun setAnalyticsEnabled_true_updatesServiceAndState() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = false, available = true)
        val viewModel = viewModel(analytics)
        val failures = failuresOf(viewModel)

        viewModel.setAnalyticsEnabled(true)

        verify { analytics.setEnabled(true) }
        assertTrue(viewModel.uiState.value.analyticsEnabled)
        assertEquals(emptyList<AnalyticsSaveFailure>(), failures)
    }

    @Test
    fun setAnalyticsEnabled_savesOffTheMainThread_andMovesTheSwitchAtOnce() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = true, available = true)
        val viewModel = viewModel(analytics).apply { ioDispatcher = StandardTestDispatcher(testScheduler) }

        viewModel.setAnalyticsEnabled(false)

        assertFalse("the switch moves before the save finishes", viewModel.uiState.value.analyticsEnabled)
        verify(exactly = 0) { analytics.setEnabled(any()) }
        advanceUntilIdle()
        verify(exactly = 1) { analytics.setEnabled(false) }
        assertFalse(viewModel.uiState.value.analyticsEnabled)
    }

    @Test
    fun setAnalyticsEnabled_failedOptIn_isReported_andTheSwitchStaysOff() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = false, available = true, saves = false)
        val viewModel = viewModel(analytics)
        val failures = failuresOf(viewModel)

        viewModel.setAnalyticsEnabled(true)

        assertEquals(listOf(AnalyticsSaveFailure.TURNING_ON), failures)
        assertFalse("an unsaved opt-in stays off", viewModel.uiState.value.analyticsEnabled)
    }

    @Test
    fun setAnalyticsEnabled_failedOptOut_showsTheSavedValueSoTogglingRetries() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = true, available = true, saves = false)
        val viewModel = viewModel(analytics)
        val failures = failuresOf(viewModel)

        viewModel.setAnalyticsEnabled(false)

        assertEquals(listOf(AnalyticsSaveFailure.TURNING_OFF), failures)
        assertTrue("the switch shows the still-saved on", viewModel.uiState.value.analyticsEnabled)
        assertTrue(
            "a reopened Settings screen shows the same saved value",
            viewModel(analytics).uiState.value.analyticsEnabled
        )
    }

    @Test
    fun rapidToggles_areSavedInOrder_andTheLastOneWins() = runTest(dispatcher) {
        val analytics = analyticsService(enabled = true, available = true)
        val viewModel = viewModel(analytics).apply { ioDispatcher = StandardTestDispatcher(testScheduler) }
        val failures = failuresOf(viewModel)

        val switch = mutableListOf<Boolean>()
        backgroundScope.launch { viewModel.uiState.map { it.analyticsEnabled }.toList(switch) }

        viewModel.setAnalyticsEnabled(false)
        viewModel.setAnalyticsEnabled(true)
        viewModel.setAnalyticsEnabled(false)
        // Each toggle moved the switch at once; no save has run yet.
        assertEquals(listOf(true, false, true, false), switch)
        verify(exactly = 0) { analytics.setEnabled(any()) }

        advanceUntilIdle()

        verifyOrder {
            analytics.setEnabled(false)
            analytics.setEnabled(true)
            analytics.setEnabled(false)
        }
        assertEquals("an older save finishing never flipped the switch back", listOf(true, false, true, false), switch)
        assertFalse(viewModel.uiState.value.analyticsEnabled)
        assertFalse("the last toggle is what is saved", analytics.isEnabled)
        assertEquals(emptyList<AnalyticsSaveFailure>(), failures)
    }

    @Test
    fun aStaleFailure_isNotReported_whenANewerToggleSaves() = runTest(dispatcher) {
        var stored = true
        var commits = 0
        val analytics = mockk<AnalyticsService>(relaxed = true) {
            every { isEnabled } answers { stored }
            every { isAvailable } returns true
            every { setEnabled(any()) } answers {
                // The first save (the opt-out) fails, the retry (an opt-in) saves.
                val saves = commits++ > 0
                if (saves) stored = firstArg()
                saves
            }
        }
        val viewModel = viewModel(analytics).apply { ioDispatcher = StandardTestDispatcher(testScheduler) }
        val failures = failuresOf(viewModel)

        viewModel.setAnalyticsEnabled(false)
        viewModel.setAnalyticsEnabled(true)
        advanceUntilIdle()

        assertEquals("the user's last choice saved; nothing to report", emptyList<AnalyticsSaveFailure>(), failures)
        assertTrue(viewModel.uiState.value.analyticsEnabled)
    }

    @Test
    fun theLastToggleFailing_isReported_andTheSwitchShowsTheSavedValue() = runTest(dispatcher) {
        var stored = true
        var commits = 0
        val analytics = mockk<AnalyticsService>(relaxed = true) {
            every { isEnabled } answers { stored }
            every { isAvailable } returns true
            every { setEnabled(any()) } answers {
                // The opt-out saves; the opt-in that follows does not.
                val saves = commits++ == 0
                if (saves) stored = firstArg()
                saves
            }
        }
        val viewModel = viewModel(analytics).apply { ioDispatcher = StandardTestDispatcher(testScheduler) }
        val failures = failuresOf(viewModel)

        viewModel.setAnalyticsEnabled(false)
        viewModel.setAnalyticsEnabled(true)
        advanceUntilIdle()

        assertEquals(listOf(AnalyticsSaveFailure.TURNING_ON), failures)
        assertFalse("the saved off", viewModel.uiState.value.analyticsEnabled)
    }
}
