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
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
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
    /** A debug-only soak send ([SettingsViewModel.runSdkSoakSend]) is in flight. */
    val soakSendInFlight: Boolean = false,
    /** Outcome of the last debug-only soak send, shown inline as the item subtitle. */
    val soakSendStatus: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletUIConfig: WalletUIConfig,
    private val walletDataProvider: WalletDataProvider,
    private val analytics: AnalyticsService,
    private val configuration: Configuration,
    private val dashPayConfig: DashPayConfig,
    private val blockchainIdentityConfig: BlockchainIdentityConfig,
    private val blockchainServiceConfig: BlockchainServiceConfig,
    private val sendPaymentService: SendPaymentService,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(
    blockchainIdentityConfig,
    dashPayProfileDao
) {
    companion object {
        private val log = LoggerFactory.getLogger(SettingsViewModel::class.java)

        /** Distinctive tag: `adb logcat`-greppable soak-send trail. */
        private val soakLog = LoggerFactory.getLogger("SdkSoakSend")

        /** Fixed debug soak-send amount: 0.05 Dash to our own fresh address. */
        private val SOAK_SEND_AMOUNT = Dash.parse("0.05")
    }

    private val powerManager: PowerManager = walletApplication.getSystemService(PowerManager::class.java)

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
                _uiState.value = _uiState.value.copy(localCurrencySymbol = currency)
            }
            .launchIn(viewModelScope)

        // Observe blockchain identity for transaction metadata visibility
        blockchainIdentityConfig.observeBase()
            .filterNotNull()
            .map { it.creationComplete }
            .distinctUntilChanged()
            .onEach { isVisible ->
                _uiState.value = _uiState.value.copy(transactionMetadataVisible = isVisible)
            }.launchIn(viewModelScope)

        // Observe the debug-only Kotlin-SDK L1 send flag (Phase 5b soak
        // toggle) so the switch reflects the current value on every screen
        // entry — including flips made via adb while the app was running.
        dashPayConfig.observe(DashPayConfig.USE_KOTLIN_SDK_L1_SEND)
            .distinctUntilChanged()
            .onEach { enabled ->
                _uiState.value = _uiState.value.copy(useKotlinSdkL1Send = enabled == true)
            }.launchIn(viewModelScope)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(walletApplication.packageName)
    }

    fun updateIgnoringBatteryOptimizations() {
        try {
            val isIgnoring = isIgnoringBatteryOptimizations()
            _uiState.value = _uiState.value.copy(ignoringBatteryOptimizations = isIgnoring)
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
        _uiState.value = _uiState.value.copy(transactionMetadataSubtitle = subtitle)
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
        _uiState.value = _uiState.value.copy(
            soakSendInFlight = true,
            soakSendStatus = "sending ${SOAK_SEND_AMOUNT.toPlainString()} to self…"
        )
        viewModelScope.launch(Dispatchers.IO) {
            // Engine label = the routing flag at send time (the neutral
            // overload only returns a txid, deliberately route-agnostic).
            val flagLabel = try {
                if (dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) == true) "SDK flag on" else "SDK flag off"
            } catch (e: Exception) {
                soakLog.warn("failed to read USE_KOTLIN_SDK_L1_SEND for the report label", e)
                "SDK flag unknown"
            }
            val status = try {
                val address = walletDataProvider.freshReceiveAddressString()
                soakLog.info(
                    "soak send: {} Dash to own fresh address {} ({})",
                    SOAK_SEND_AMOUNT.toPlainString(), address, flagLabel
                )
                val txid = sendPaymentService.sendCoins(
                    address,
                    SOAK_SEND_AMOUNT,
                    emptyWallet = false,
                    checkBalanceConditions = true
                )
                soakLog.info("soak send broadcast: txid {} ({})", txid, flagLabel)
                "sent ${txid.take(8)}… ($flagLabel)"
            } catch (e: Exception) {
                soakLog.error("soak send failed ({})", flagLabel, e)
                "failed: ${e.message ?: e.javaClass.simpleName} ($flagLabel)"
            }
            _uiState.value = _uiState.value.copy(soakSendInFlight = false, soakSendStatus = status)
        }
    }

    suspend fun setWalletCreationDate(creationDate: Long?) {
        blockchainServiceConfig.setWalletCreationDate(creationDate)
    }

    suspend fun getWalletCreationDate(): Long? =
        blockchainServiceConfig.getWalletCreationDate()
}
