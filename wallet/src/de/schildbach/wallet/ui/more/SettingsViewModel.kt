/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.more

import android.os.PowerManager
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkTxMetadataDecryptProbe
import de.schildbach.wallet.service.platform.sdk.WalletFundingGate
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class SettingsUIState(
    val ignoringBatteryOptimizations: Boolean = false,
    val localCurrencySymbol: String = Constants.USD_CURRENCY,
    val transactionMetadataVisible: Boolean = false,
    val transactionMetadataSubtitle: String? = null,
    /** Debug-only Phase 5b soak switch ([DashPayConfig.USE_KOTLIN_SDK_L1_SEND]). */
    val useKotlinSdkL1Send: Boolean = false,
    /**
     * Debug-only live status line under the SDK-send toggle
     * ([sdkEngineStatusLine] over [SdkL1SendService.probeSendGate], polled
     * while the screen collects this state). Null until the first poll —
     * and always null in release builds, where the poller never starts.
     */
    val sdkSendGateStatus: String? = null,
    /** A debug-only soak send ([SettingsViewModel.runSdkSoakSend]) is in flight. */
    val soakSendInFlight: Boolean = false,
    /** Outcome of the last debug-only soak send, shown inline as the item subtitle. */
    val soakSendStatus: String? = null,
    /** A debug-only decrypt proof ([SettingsViewModel.runTxMetadataDecryptProof]) is in flight. */
    val txMetadataProofInFlight: Boolean = false,
    /**
     * Outcome of the last debug-only wire-compat decrypt proof (the
     * [SdkTxMetadataDecryptProbe] summary/verdict line), shown inline as
     * the item subtitle.
     */
    val txMetadataProofStatus: String? = null,
)

/**
 * The debug "SDK engine" status line: send-gate verdict → user-visible
 * text. Pure for host tests. The closed-state line carries the gate's own
 * reason (e.g. "SDK shadow SPV not synced yet") because that is exactly
 * what a soak tester needs to see. Debug-only strings — never shipped,
 * never translated (same rationale as the SettingsScreen debug block).
 */
internal fun sdkEngineStatusLine(gate: WalletFundingGate): String = if (gate.allowed) {
    "SDK engine: READY"
} else {
    "SDK engine: syncing — sends will fall back to dashj (${gate.reason})"
}

/**
 * The soak-report route label: which engine the routed (neutral) send
 * actually took, derived from the flag plus the send gate probed at
 * attempt time — the same predicate [SdkL1SendService] evaluates moments
 * later. Best-effort: with the flag on and the gate open another
 * preflight (e.g. wallet not bound to the SDK) can still fall back to
 * dashj, but the gate is the only condition observed to flip in practice.
 * Pure for host tests; debug-only strings.
 */
internal fun soakRouteLabel(sdkFlagOn: Boolean?, gateOpenAtAttempt: Boolean): String = when {
    sdkFlagOn == null -> "route unknown"
    !sdkFlagOn -> "dashj — flag off"
    gateOpenAtAttempt -> "SDK engine"
    else -> "dashj fallback"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletUIConfig: WalletUIConfig,
    private val walletDataProvider: WalletData,
    private val analytics: AnalyticsService,
    private val configuration: Configuration,
    private val dashPayConfig: DashPayConfig,
    private val blockchainIdentityConfig: BlockchainIdentityConfig,
    private val blockchainServiceConfig: BlockchainServiceConfig,
    private val sendPaymentService: SendPaymentService,
    private val sdkL1SendService: SdkL1SendService,
    private val sdkTxMetadataDecryptProbe: SdkTxMetadataDecryptProbe,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(
    blockchainIdentityConfig,
    dashPayProfileDao
) {
    companion object {
        private val log = LoggerFactory.getLogger(SettingsViewModel::class.java)

        /** Distinctive tag: `adb logcat`-greppable soak-send trail. */
        private val soakLog = LoggerFactory.getLogger("SdkSoakSend")

        /** Distinctive tag: shared with [SdkTxMetadataDecryptProbe]'s trail. */
        private val proofLog = LoggerFactory.getLogger("TxMetaDecryptProof")

        /** Fixed debug soak-send amount: 0.05 Dash to our own fresh address. */
        private val SOAK_SEND_AMOUNT = Dash.parse("0.05")

        /** Debug-only SDK send-gate poll cadence ([sdkSendGateStatusPoll]). */
        private const val SDK_GATE_POLL_MS = 2_000L
    }

    private val powerManager: PowerManager = walletApplication.getSystemService(PowerManager::class.java)

    /** Test seam: the soak send / decrypt proof block on network and must stay off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _uiState = MutableStateFlow(SettingsUIState())
    val uiState: StateFlow<SettingsUIState> = _uiState.asStateFlow()

    init {
        // Initialize with current battery optimization status
        updateIgnoringBatteryOptimizations()

        // Observe all data sources and update UI state
        observeDataSources()
    }

    private fun observeDataSources() {
        // Observe selected currency
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { currency ->
                _uiState.update { it.copy(localCurrencySymbol = currency) }
            }
            .launchIn(viewModelScope)

        // Observe blockchain identity for transaction metadata visibility
        blockchainIdentityConfig.observeBase()
            .filterNotNull()
            .map { it.creationComplete }
            .distinctUntilChanged()
            .onEach { isVisible ->
                _uiState.update { it.copy(transactionMetadataVisible = isVisible) }
            }.launchIn(viewModelScope)

        // Observe the debug-only Kotlin-SDK L1 send flag (Phase 5b soak
        // toggle) so the switch reflects the current value on every screen
        // entry — including flips made via adb while the app was running.
        dashPayConfig.observe(DashPayConfig.USE_KOTLIN_SDK_L1_SEND)
            .distinctUntilChanged()
            .onEach { enabled ->
                _uiState.update { it.copy(useKotlinSdkL1Send = enabled == true) }
            }.launchIn(viewModelScope)

        // Debug-only: poll the SDK L1 send gate — the EXACT predicate
        // SdkL1SendService evaluates per send (probeSendGate) — while the
        // screen is collecting uiState, so soak taps right after app-open
        // (shadow SPV not SYNCED yet, ~60s) can SEE whether the next send
        // takes the SDK engine or falls back to dashj. Keyed off the
        // uiState subscription count: Compose's collectAsState unsubscribes
        // when the screen leaves composition, which stops the poll.
        if (BuildConfig.DEBUG) {
            observeSdkSendGate()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSdkSendGate() {
        _uiState.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { visible -> if (visible) sdkSendGateStatusPoll() else emptyFlow() }
            .onEach { status ->
                _uiState.update { it.copy(sdkSendGateStatus = status) }
            }.launchIn(viewModelScope)
    }

    /** Emits the gate status line immediately, then every [SDK_GATE_POLL_MS]. */
    private fun sdkSendGateStatusPoll() = flow {
        while (true) {
            emit(sdkEngineStatusLine(probeSendGateSafe()))
            delay(SDK_GATE_POLL_MS)
        }
    }

    /** [SdkL1SendService.probeSendGate] with failures contained (closed gate). */
    private fun probeSendGateSafe(): WalletFundingGate = try {
        sdkL1SendService.probeSendGate()
    } catch (e: Exception) {
        log.warn("failed to probe the SDK send gate", e)
        WalletFundingGate(false, "gate probe failed")
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(walletApplication.packageName)
    }

    fun updateIgnoringBatteryOptimizations() {
        try {
            val isIgnoring = isIgnoringBatteryOptimizations()
            _uiState.update { it.copy(ignoringBatteryOptimizations = isIgnoring) }
        } catch (e: Exception) {
            log.error("Error updating battery optimization status", e)
        }
    }

    fun logEvent(event: String) {
        try {
            analytics.logEvent(event, mapOf())
        } catch (e: Exception) {
            log.error("Error logging analytics event: $event", e)
        }
    }

    fun updateLastBlockchainResetTime() {
        configuration.updateLastBlockchainResetTime()
    }

    fun getTotalWalletBalance() = walletDataProvider.getWalletBalance()

    suspend fun isTransactionMetadataInfoShown() = dashPayConfig.isTransactionMetadataInfoShown()

    suspend fun isSavingTransactionMetadata() = dashPayConfig.isSavingTransactionMetadata()

    fun updateTransactionMetadataSubtitle(subtitle: String?) {
        _uiState.update { it.copy(transactionMetadataSubtitle = subtitle) }
    }

    /**
     * Flip the debug-only Phase 5b soak flag
     * ([DashPayConfig.USE_KOTLIN_SDK_L1_SEND] — routes real L1 sends
     * through the Kotlin SDK; deliberately never debug-seeded, opt-in
     * only). The state flows back through the observer above, so the
     * switch always shows the persisted value.
     */
    fun setUseKotlinSdkL1Send(enabled: Boolean) {
        viewModelScope.launch {
            try {
                dashPayConfig.set(DashPayConfig.USE_KOTLIN_SDK_L1_SEND, enabled)
                log.info("debug toggle: USE_KOTLIN_SDK_L1_SEND set to {}", enabled)
            } catch (e: Exception) {
                log.error("failed to set USE_KOTLIN_SDK_L1_SEND", e)
            }
        }
    }

    /**
     * Debug-only Phase 5b soak send: 0.05 Dash to a FRESH OWN receive
     * address through [SendPaymentService]'s NEUTRAL `sendCoins(String,
     * Dash)` overload — the only send routed through the Kotlin SDK. With
     * [DashPayConfig.USE_KOTLIN_SDK_L1_SEND] ON this exercises the SDK
     * engine end to end; with it OFF it is a dashj control send over the
     * identical call path. Signing is non-interactive on both routes
     * (SecurityGuard-retrieved password / the SDK's mnemonic resolver —
     * the same way CrowdNode invokes this runner), so no PIN/biometric
     * prompt is needed here. Re-taps while a send is in flight are
     * ignored; the outcome lands in [SettingsUIState.soakSendStatus].
     */
    fun runSdkSoakSend() {
        if (_uiState.value.soakSendInFlight) {
            soakLog.info("soak send already in flight; ignoring tap")
            return
        }
        _uiState.update {
            it.copy(
                soakSendInFlight = true,
                soakSendStatus = "sending ${SOAK_SEND_AMOUNT.toPlainString()} to self…"
            )
        }
        viewModelScope.launch(ioDispatcher) {
            // Route label = the flag PLUS the send gate probed at attempt
            // time (the neutral overload only returns a txid, deliberately
            // route-agnostic; see soakRouteLabel for the best-effort caveat).
            val sdkFlagOn: Boolean? = try {
                dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) == true
            } catch (e: Exception) {
                soakLog.warn("failed to read USE_KOTLIN_SDK_L1_SEND for the report label", e)
                null
            }
            val routeLabel = soakRouteLabel(sdkFlagOn, probeSendGateSafe().allowed)
            val status = try {
                val address = walletDataProvider.freshReceiveAddressString()
                soakLog.info(
                    "soak send: {} Dash to own fresh address {} ({})",
                    SOAK_SEND_AMOUNT.toPlainString(), address, routeLabel
                )
                val txid = sendPaymentService.sendCoins(
                    address,
                    SOAK_SEND_AMOUNT,
                    emptyWallet = false,
                    checkBalanceConditions = true
                )
                soakLog.info("soak send broadcast: txid {} ({})", txid, routeLabel)
                "sent ${txid.take(8)}… ($routeLabel)"
            } catch (e: Exception) {
                soakLog.error("soak send failed ({})", routeLabel, e)
                "failed: ${e.message ?: e.javaClass.simpleName} ($routeLabel)"
            }
            _uiState.update { it.copy(soakSendInFlight = false, soakSendStatus = status) }
        }
    }

    /**
     * Debug-only wire-compat decrypt proof (dashpay/platform#4091): fetch
     * this identity's LEGACY-written encrypted `txMetadata` documents
     * through the new SDK's `fetchEncryptedDocuments` and check they
     * decrypt + parse as the legacy protobuf batch — proving the SDK's
     * encryption scheme (incl. the statically-unpinnable HD derivation
     * prefix) matches what `publishTxMetaData` wrote. Read-only: the probe
     * never creates documents or touches wallet state; all failures are
     * contained into the status line. Verbose trail under logcat tag
     * `TxMetaDecryptProof`; re-taps while in flight are ignored.
     */
    fun runTxMetadataDecryptProof() {
        if (_uiState.value.txMetadataProofInFlight) {
            proofLog.info("decrypt proof already in flight; ignoring tap")
            return
        }
        _uiState.update {
            it.copy(
                txMetadataProofInFlight = true,
                txMetadataProofStatus = "fetching + decrypting via the SDK…"
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val status = try {
                sdkTxMetadataDecryptProbe.runProof()
            } catch (e: Exception) {
                // The probe contains its own failures; this is belt-and-braces.
                proofLog.error("decrypt proof threw unexpectedly", e)
                "failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _uiState.update {
                it.copy(
                    txMetadataProofInFlight = false,
                    txMetadataProofStatus = status
                )
            }
        }
    }

    suspend fun setWalletCreationDate(creationDate: Long?) {
        blockchainServiceConfig.setWalletCreationDate(creationDate)
    }

    suspend fun getWalletCreationDate(): Long? =
        blockchainServiceConfig.getWalletCreationDate()
}
