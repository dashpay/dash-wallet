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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class SettingsUIState(
    val ignoringBatteryOptimizations: Boolean = false,
    val localCurrencySymbol: String = Constants.USD_CURRENCY,
    val transactionMetadataVisible: Boolean = false,
    val transactionMetadataSubtitle: String? = null,
    val analyticsEnabled: Boolean = true,
    val analyticsAvailable: Boolean = true
)

/** A Settings analytics choice that could not be saved (one-shot; the fragment shows a Toast). */
enum class AnalyticsSaveFailure {
    /** The opt-out did not save: collection is off for this process only. */
    TURNING_OFF,

    /** The opt-in did not save: collection stays off. */
    TURNING_ON
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
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(
    blockchainIdentityConfig,
    dashPayProfileDao
) {
    companion object {
        private val log = LoggerFactory.getLogger(SettingsViewModel::class.java)
    }

    private val powerManager: PowerManager = walletApplication.getSystemService(PowerManager::class.java)

    private val _uiState = MutableStateFlow(SettingsUIState(
            analyticsEnabled = analytics.isEnabled,
            analyticsAvailable = analytics.isAvailable
        ))
    val uiState: StateFlow<SettingsUIState> = _uiState.asStateFlow()

    /** Test seam: [AnalyticsService.setEnabled] blocks on a disk write and must stay off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // Mutex is FIFO, so toggles reach the service in the order the user made them.
    private val analyticsSaveMutex = Mutex()
    // The newest toggle; only its result may move the switch or report a failure.
    // Touched on the main thread only (viewModelScope).
    private var latestAnalyticsRequest = 0L
    private val _analyticsSaveFailures = Channel<AnalyticsSaveFailure>(Channel.BUFFERED)
    val analyticsSaveFailures: Flow<AnalyticsSaveFailure> = _analyticsSaveFailures.receiveAsFlow()

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

    /**
     * Saves the choice off the main thread; see [AnalyticsService.setEnabled]. The switch
     * moves to [enabled] at once and settles on the saved value when the save finishes,
     * so after a failure it shows the saved value and toggling it again retries. A failed
     * save is reported through [analyticsSaveFailures].
     *
     * Toggles are saved one at a time in the order they were made, so the last one is
     * also the last to be saved. Only its result updates the switch or reports a failure:
     * an older toggle finishing while a newer one waits cannot flip the switch back.
     */
    fun setAnalyticsEnabled(enabled: Boolean) {
        val request = ++latestAnalyticsRequest
        _uiState.update { it.copy(analyticsEnabled = enabled) }
        viewModelScope.launch {
            // NonCancellable: a toggle made just before leaving Settings must still be
            // saved (and stay in order) after the ViewModel is cleared.
            val (persisted, saved) = withContext(NonCancellable) {
                analyticsSaveMutex.withLock {
                    withContext(ioDispatcher) { analytics.setEnabled(enabled) to analytics.isEnabled }
                }
            }
            if (request != latestAnalyticsRequest) {
                return@launch
            }
            _uiState.update { it.copy(analyticsEnabled = saved) }
            if (!persisted) {
                _analyticsSaveFailures.trySend(
                    if (enabled) AnalyticsSaveFailure.TURNING_ON else AnalyticsSaveFailure.TURNING_OFF
                )
            }
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

    suspend fun setWalletCreationDate(creationDate: Long?) {
        blockchainServiceConfig.setWalletCreationDate(creationDate)
    }

    suspend fun getWalletCreationDate(): Long? =
        blockchainServiceConfig.getWalletCreationDate()
}
