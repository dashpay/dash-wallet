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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the debug-only Phase 5b soak toggle wiring in
 * [SettingsViewModel]: the switch state mirrors the persisted
 * [DashPayConfig.USE_KOTLIN_SDK_L1_SEND] value on entry AND live (so an
 * adb-side flip shows up), and flipping it writes through to the config —
 * the flag round-trip the debug settings switch relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Backing "datastore" for the flag: null = unset (the release default). */
    private val l1SendFlag = MutableStateFlow<Boolean?>(null)

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
    private val dashPayConfig = mockk<DashPayConfig> {
        every { observe(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) } returns l1SendFlag
        coEvery { set(DashPayConfig.USE_KOTLIN_SDK_L1_SEND, any()) } answers {
            l1SendFlag.value = secondArg()
        }
    }
    private val blockchainIdentityConfig = mockk<BlockchainIdentityConfig> {
        every { observe() } returns emptyFlow()
        every { observeBase() } returns emptyFlow()
    }
    private val dashPayProfileDao = mockk<DashPayProfileDao>()
    private val walletDataProvider = mockk<WalletDataProvider> {
        every { freshReceiveAddressString() } returns "yOwnFreshAddress"
    }
    private val sendPaymentService = mockk<SendPaymentService>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) } answers { l1SendFlag.value }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        walletApplication = walletApplication,
        walletUIConfig = walletUIConfig,
        walletDataProvider = walletDataProvider,
        analytics = mockk<AnalyticsService>(relaxed = true),
        configuration = mockk<Configuration>(),
        dashPayConfig = dashPayConfig,
        blockchainIdentityConfig = blockchainIdentityConfig,
        blockchainServiceConfig = mockk<BlockchainServiceConfig>(),
        sendPaymentService = sendPaymentService,
        dashPayProfileDao = dashPayProfileDao
    )

    @Test
    fun unsetFlag_switchShowsOff() = runTest(dispatcher) {
        // The flag is deliberately NOT debug-seeded — unset must read as off.
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.useKotlinSdkL1Send)
    }

    @Test
    fun persistedFlag_isReflected_onEntryAndLive() = runTest(dispatcher) {
        // Set before the screen opens (e.g. via adb) → reflected on entry.
        l1SendFlag.value = true
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.useKotlinSdkL1Send)

        // Flipped externally while the screen is open → reflected live.
        l1SendFlag.value = false
        assertFalse(viewModel.uiState.value.useKotlinSdkL1Send)
    }

    @Test
    fun toggling_writesTheFlag_andTheStateRoundTripsThroughTheConfig() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.useKotlinSdkL1Send)

        viewModel.setUseKotlinSdkL1Send(true)
        coVerify(exactly = 1) { dashPayConfig.set(DashPayConfig.USE_KOTLIN_SDK_L1_SEND, true) }
        assertTrue(viewModel.uiState.value.useKotlinSdkL1Send)

        viewModel.setUseKotlinSdkL1Send(false)
        coVerify(exactly = 1) { dashPayConfig.set(DashPayConfig.USE_KOTLIN_SDK_L1_SEND, false) }
        assertFalse(viewModel.uiState.value.useKotlinSdkL1Send)
    }

    @Test
    fun configWriteFailure_isContained() = runTest(dispatcher) {
        coEvery { dashPayConfig.set(DashPayConfig.USE_KOTLIN_SDK_L1_SEND, any()) } throws
            IllegalStateException("datastore unavailable")
        val viewModel = viewModel()

        viewModel.setUseKotlinSdkL1Send(true) // must not throw

        assertFalse(viewModel.uiState.value.useKotlinSdkL1Send) // state stays honest
    }

    // ── The debug-only soak send (runSdkSoakSend) ─────────────────────

    private val soakTxid = "ab".repeat(32)

    /** Waits out the IO-dispatched send and returns the settled state. */
    private suspend fun SettingsViewModel.settledSoakState() =
        uiState.first { !it.soakSendInFlight && it.soakSendStatus != null }

    @Test
    fun soakSend_sendsToOwnFreshAddress_viaTheNeutralOverload_andReportsTheFlag() = runTest(dispatcher) {
        l1SendFlag.value = true
        coEvery {
            sendPaymentService.sendCoins("yOwnFreshAddress", Dash.parse("0.05"), false, true)
        } returns soakTxid
        val viewModel = viewModel()

        viewModel.runSdkSoakSend()

        val state = viewModel.settledSoakState()
        assertTrue(state.soakSendStatus!!.startsWith("sent ${soakTxid.take(8)}"))
        assertTrue(state.soakSendStatus!!.contains("SDK flag on"))
        coVerify(exactly = 1) {
            sendPaymentService.sendCoins("yOwnFreshAddress", Dash.parse("0.05"), false, true)
        }
    }

    @Test
    fun soakSendFailure_surfacesTheExceptionMessage() = runTest(dispatcher) {
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } throws IllegalStateException("insufficient funds")
        val viewModel = viewModel()

        viewModel.runSdkSoakSend()

        val state = viewModel.settledSoakState()
        assertTrue(state.soakSendStatus!!.startsWith("failed: insufficient funds"))
        assertTrue(state.soakSendStatus!!.contains("SDK flag off"))
    }

    @Test
    fun soakSendReTaps_areIgnoredWhileInFlight() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } coAnswers {
            gate.await()
            soakTxid
        }
        val viewModel = viewModel()

        viewModel.runSdkSoakSend()
        viewModel.runSdkSoakSend() // re-tap while in flight — must be a no-op
        gate.complete(Unit)

        viewModel.settledSoakState()
        coVerify(exactly = 1) {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        }
    }
}
