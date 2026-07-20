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
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkL1SendSource
import de.schildbach.wallet.service.platform.sdk.SdkTxMetadataDecryptProbe
import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.service.platform.sdk.TxMetadataDecryptProbeSource
import de.schildbach.wallet.service.platform.sdk.WalletFundingGate
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CompletableDeferred
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val walletDataProvider = mockk<WalletData> {
        every { freshReceiveAddressString() } returns "yOwnFreshAddress"
    }
    private val sendPaymentService = mockk<SendPaymentService>()

    /** Latest SDK sync progress the gate probe sees; IDLE = gate closed. */
    @Volatile private var latestProgress: ShadowSyncProgress = ShadowSyncProgress.IDLE

    /** Counts gate-probe progress reads — the poll-lifecycle instrument. */
    @Volatile private var progressReads = 0

    /**
     * The open-gate evidence: the SDK filter scan caught up to the chain
     * tip. Phase is deliberately NOT SYNCED (a live shadow chasing the tip
     * never latches SYNCED) — the gate opens on caught-up alone.
     */
    private fun openGateProgress() = ShadowSyncProgress(
        phase = ShadowSyncPhase.FILTERS,
        overallPercent = 1.0,
        headerHeight = 1_500_000,
        headerTarget = 1_500_000,
        filterHeight = 1_500_000,
        filterTarget = 1_500_000
    )

    /**
     * A REAL [SdkL1SendService] (fakes only around it), so the status line
     * and route label are proven against the service's own gate predicate,
     * not a re-implementation.
     */
    private val sdkL1SendService = SdkL1SendService(
        source = object : SdkL1SendSource {
            override suspend fun boundWalletIdOrNull(): String? = null
            override suspend fun sendToAddress(
                walletIdHex: String,
                addressBase58: String,
                amountDuffs: Long
            ): String = throw IllegalStateException("the probe must never send")
        },
        dashPayConfig = dashPayConfig,
        isValidAddress = { true },
        l1Progress = {
            progressReads++
            latestProgress
        }
    )

    /** The decrypt-proof source: fetch result swappable per test. */
    @Volatile private var proofFetchResult: () -> String = { "[]" }

    /** Blocks the proof fetch until completed — the re-tap instrument. */
    private val proofFetchGate = CompletableDeferred<Unit>()

    /** Counts proof fetches — proves re-taps while in flight are ignored. */
    @Volatile private var proofFetches = 0

    /**
     * A REAL [SdkTxMetadataDecryptProbe] (fakes only at the seams), so the
     * subtitle status is proven against the probe's own verdict/summary
     * mapping, not a re-implementation.
     */
    private val sdkTxMetadataDecryptProbe = SdkTxMetadataDecryptProbe(
        source = object : TxMetadataDecryptProbeSource {
            override suspend fun boundWalletIdOrNull(): String? = "wallet-id"
            override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray) = true
            override suspend fun fetchEncryptedDocuments(
                walletIdHex: String,
                ownerId: ByteArray,
                contractId: ByteArray,
                documentType: String,
                sinceMs: Long
            ): String {
                proofFetches++
                proofFetchGate.await()
                return proofFetchResult()
            }
        },
        ownIdentityId = { org.dashj.platform.dpp.identifier.Identifier.from(ByteArray(32) { 7 }).toString() },
        contractId = { org.dashj.platform.dpp.identifier.Identifier.from(ByteArray(32) { 9 }) },
        legacyDocumentCount = { 0 }
    )

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
        sdkL1SendService = sdkL1SendService,
        sdkTxMetadataDecryptProbe = sdkTxMetadataDecryptProbe,
        dashPayProfileDao = dashPayProfileDao
    ).apply {
        // Off-main work must run inside runTest's virtual time, or the
        // settled-state waits below race a real IO thread (flaky 1m hangs).
        ioDispatcher = dispatcher
    }

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

    /** Runs the dispatched send to completion (virtual time) and returns the settled state. */
    private fun TestScope.settledSoakState(viewModel: SettingsViewModel): SettingsUIState {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse("the soak send should have settled", state.soakSendInFlight)
        return state
    }

    @Test
    fun soakSend_flagOnGateOpen_reportsTheSdkEngineRoute() = runTest(dispatcher) {
        l1SendFlag.value = true
        latestProgress = openGateProgress()
        coEvery {
            sendPaymentService.sendCoins("yOwnFreshAddress", Dash.parse("0.05"), false, true)
        } returns soakTxid
        val viewModel = viewModel()

        viewModel.runSdkSoakSend()

        val state = settledSoakState(viewModel)
        assertTrue(state.soakSendStatus!!.startsWith("sent ${soakTxid.take(8)}"))
        assertTrue(state.soakSendStatus!!.contains("(SDK engine)"))
        coVerify(exactly = 1) {
            sendPaymentService.sendCoins("yOwnFreshAddress", Dash.parse("0.05"), false, true)
        }
    }

    @Test
    fun soakSend_flagOnGateClosed_reportsTheDashjFallbackRoute() = runTest(dispatcher) {
        // The live-soak trap this label exists for: flag ON but the SDK
        // filter scan not caught up yet, so SdkL1SendService declines and
        // dashj sends.
        l1SendFlag.value = true
        latestProgress = openGateProgress().copy(filterHeight = 1_400_000)
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } returns soakTxid
        val viewModel = viewModel()

        viewModel.runSdkSoakSend()

        val state = settledSoakState(viewModel)
        assertTrue(state.soakSendStatus!!.contains("(dashj fallback)"))
    }

    @Test
    fun soakSendFailure_surfacesTheExceptionMessage_andTheFlagOffRoute() = runTest(dispatcher) {
        coEvery {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        } throws IllegalStateException("insufficient funds")
        val viewModel = viewModel()

        viewModel.runSdkSoakSend()

        val state = settledSoakState(viewModel)
        assertTrue(state.soakSendStatus!!.startsWith("failed: insufficient funds"))
        assertTrue(state.soakSendStatus!!.contains("(dashj — flag off)"))
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

        settledSoakState(viewModel)
        coVerify(exactly = 1) {
            sendPaymentService.sendCoins(any<String>(), any<Dash>(), any(), any())
        }
    }

    // ── The debug-only decrypt proof (runTxMetadataDecryptProof) ──────

    /** Runs the dispatched proof to completion (virtual time) and returns the settled state. */
    private fun TestScope.settledProofState(viewModel: SettingsViewModel): SettingsUIState {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse("the decrypt proof should have settled", state.txMetadataProofInFlight)
        return state
    }

    @Test
    fun decryptProof_surfacesTheProbeSummaryLine_asTheSubtitle() = runTest(dispatcher) {
        proofFetchResult = { "[]" } // nothing on Platform for this identity
        proofFetchGate.complete(Unit)
        val viewModel = viewModel()

        viewModel.runTxMetadataDecryptProof()

        val state = settledProofState(viewModel)
        assertEquals(
            "sdkFetched=0 sdkDecrypted=0 sdkParsed=0 legacyExpected=0 verdict=FAILED",
            state.txMetadataProofStatus
        )
    }

    @Test
    fun decryptProofReTaps_areIgnoredWhileInFlight() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.runTxMetadataDecryptProof()
        viewModel.runTxMetadataDecryptProof() // re-tap while in flight — must be a no-op
        proofFetchGate.complete(Unit)

        settledProofState(viewModel)
        assertEquals(1, proofFetches)
    }

    // ── The SDK send-gate status line (pure mappings) ─────────────────

    @Test
    fun sdkEngineStatusLine_mapsBothGateStates() {
        assertEquals(
            "SDK engine: READY",
            sdkEngineStatusLine(WalletFundingGate(true, "SDK L1 filter scan caught up to the chain tip"))
        )
        assertEquals(
            "SDK engine: syncing — sends will fall back to dashj " +
                "(SDK L1 filter scan has not caught up to the chain tip yet)",
            sdkEngineStatusLine(
                WalletFundingGate(false, "SDK L1 filter scan has not caught up to the chain tip yet")
            )
        )
    }

    @Test
    fun soakRouteLabel_mapsTheThreeRoutes_andTheUnknownFlag() {
        assertEquals("SDK engine", soakRouteLabel(sdkFlagOn = true, gateOpenAtAttempt = true))
        assertEquals("dashj fallback", soakRouteLabel(sdkFlagOn = true, gateOpenAtAttempt = false))
        assertEquals("dashj — flag off", soakRouteLabel(sdkFlagOn = false, gateOpenAtAttempt = true))
        assertEquals("dashj — flag off", soakRouteLabel(sdkFlagOn = false, gateOpenAtAttempt = false))
        assertEquals("route unknown", soakRouteLabel(sdkFlagOn = null, gateOpenAtAttempt = true))
    }

    // ── The SDK send-gate poll (lifecycle + live updates) ─────────────

    @Test
    fun gateStatus_reflectsBothStates_andUpdatesLiveWhilePolling() = runTest(dispatcher) {
        latestProgress = ShadowSyncProgress.IDLE // gate closed: engine not running yet
        val viewModel = viewModel()

        val screen = launch { viewModel.uiState.collect { } }
        runCurrent()
        assertTrue(
            viewModel.uiState.value.sdkSendGateStatus!!
                .startsWith("SDK engine: syncing — sends will fall back to dashj")
        )

        // The SDK filter scan catches up to the tip → the next poll flips to READY.
        latestProgress = openGateProgress()
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals("SDK engine: READY", viewModel.uiState.value.sdkSendGateStatus)

        // …and back (the filter scan falls behind the tip again).
        latestProgress = openGateProgress().copy(filterHeight = 1_400_000)
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(
            "SDK engine: syncing — sends will fall back to dashj " +
                "(SDK L1 filter scan has not caught up to the chain tip yet)",
            viewModel.uiState.value.sdkSendGateStatus
        )

        screen.cancel()
    }

    @Test
    fun gatePolling_runsOnlyWhileTheUiStateIsCollected() = runTest(dispatcher) {
        latestProgress = ShadowSyncProgress.IDLE
        val viewModel = viewModel()

        // Screen not visible (no uiState collector) → no probes at all.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, progressReads)

        // Screen visible → an immediate probe, then one per ~2s tick.
        val screen = launch { viewModel.uiState.collect { } }
        runCurrent()
        assertTrue(progressReads >= 1)
        advanceTimeBy(4_100)
        runCurrent()
        assertTrue(progressReads >= 3)

        // Screen gone → the poll stops (WhileSubscribed via subscriptionCount).
        screen.cancel()
        runCurrent()
        val readsAfterLeaving = progressReads
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(readsAfterLeaving, progressReads)
    }
}
