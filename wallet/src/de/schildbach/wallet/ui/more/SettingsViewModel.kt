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
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.slf4j.LoggerFactory
import org.dash.wallet.common.util.toBigDecimal
import java.text.DecimalFormat
import javax.inject.Inject

data class SettingsUIState(
    val hideBalance: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val coinJoinMixingMode: CoinJoinMode = CoinJoinMode.NONE,
    val mixingProgress: Double = 0.0,
    val localCurrencySymbol: String = Constants.USD_CURRENCY,
    val coinJoinMixingStatus: MixingStatus = MixingStatus.NOT_STARTED,
    val totalBalance: Coin = Coin.ZERO,
    val mixedBalance: Coin = Coin.ZERO,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletUIConfig: WalletUIConfig,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinService,
    private val walletDataProvider: WalletDataProvider,
    private val analytics: AnalyticsService,
    private val configuration: Configuration,
    private val dashPayConfig: DashPayConfig,
    blockchainIdentityConfig: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(
    blockchainIdentityConfig,
    dashPayProfileDao
) {
    companion object {
        private val log = LoggerFactory.getLogger(SettingsViewModel::class.java)
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
        // Observe hide balance setting
        walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE)
            .filterNotNull()
            .onEach { hideBalance ->
                _uiState.value = _uiState.value.copy(hideBalance = hideBalance)
            }
            .launchIn(viewModelScope)

        // Observe CoinJoin mixing mode
        coinJoinConfig.observeMode()
            .onEach { mode ->
                _uiState.value = _uiState.value.copy(coinJoinMixingMode = mode)
            }
            .launchIn(viewModelScope)

        // Observe mixing state
        coinJoinService.observeMixingState()
            .onEach { status ->
                _uiState.value = _uiState.value.copy(coinJoinMixingStatus = status)
            }
            .launchIn(viewModelScope)

        // Observe mixing progress
        coinJoinService.observeMixingProgress()
            .onEach { progress ->
                _uiState.value = _uiState.value.copy(mixingProgress = progress)
            }
            .launchIn(viewModelScope)

        // Observe selected currency
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { currency ->
                _uiState.value = _uiState.value.copy(localCurrencySymbol = currency)
            }
            .launchIn(viewModelScope)

        // Observe mixed balance
        walletDataProvider.observeMixedBalance()
            .filterNotNull()
            .onEach { balance ->
                _uiState.value = _uiState.value.copy(mixedBalance = balance)
            }
            .launchIn(viewModelScope)

        // Observe total balance
        walletDataProvider.observeTotalBalance()
            .filterNotNull()
            .onEach { balance ->
                _uiState.value = _uiState.value.copy(totalBalance = balance)
            }
            .launchIn(viewModelScope)
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

    suspend fun shouldShowCoinJoinInfo(): Boolean {
        return coinJoinConfig.get(CoinJoinConfig.FIRST_TIME_INFO_SHOWN) != true
    }

    suspend fun setCoinJoinInfoShown() {
        coinJoinConfig.set(CoinJoinConfig.FIRST_TIME_INFO_SHOWN, true)
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
}
