/*
 * Copyright 2022 Dash Core Group.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.toFormattedString
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class SecurityUIState(
    val needPassphraseBackup: Boolean = false,
    val fingerprintIsAvailable: Boolean = false,
    val fingerprintIsEnabled: Boolean = false,
    val hideBalance: Boolean = false,
    val balance: Coin = Coin.ZERO,
    val balanceInLocalFormat: String = "",
    val error: Exception? = null,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val exchangeRates: ExchangeRatesProvider,
    private val configuration: Configuration,
    private val walletUIConfig: WalletUIConfig,
    private val walletData: WalletDataProvider,
    private val analytics: AnalyticsService,
    private val walletApplication: WalletApplication,
    val biometricHelper: BiometricHelper,
    blockchainIdentityConfig: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    private val platformSyncService: PlatformSyncService,
): BaseProfileViewModel(blockchainIdentityConfig, dashPayProfileDao) {
    companion object {
        private val log = LoggerFactory.getLogger(SecurityViewModel::class.java)
    }

    private var selectedExchangeRate: ExchangeRate? = null

    private val _uiState = MutableStateFlow(SecurityUIState())
    val uiState: StateFlow<SecurityUIState> = _uiState.asStateFlow()

    init {
        observeDataSources()
        updateInitialState()
    }

    private fun observeDataSources() {
        // Observe selected currency and exchange rate
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { exchangeRate ->
                selectedExchangeRate = exchangeRate
                updateBalanceInLocalFormat()
            }
            .launchIn(viewModelScope)

        // Observe hide balance setting
        walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE)
            .filterNotNull()
            .onEach { hideBalance ->
                _uiState.value = _uiState.value.copy(hideBalance = hideBalance)
            }
            .launchIn(viewModelScope)

        // Observe wallet balance
        walletData.observeTotalBalance()
            .filterNotNull()
            .onEach { balance ->
                _uiState.value = _uiState.value.copy(balance = balance)
                updateBalanceInLocalFormat()
            }
            .launchIn(viewModelScope)
    }

    private fun updateInitialState() {
        _uiState.value = _uiState.value.copy(
            needPassphraseBackup = configuration.remindBackupSeed,
            fingerprintIsAvailable = biometricHelper.isAvailable,
            fingerprintIsEnabled = biometricHelper.isEnabled,
            balance = walletData.wallet?.getBalance(Wallet.BalanceType.ESTIMATED) ?: Coin.ZERO
        )

        // Ensure configuration is in sync
        configuration.enableFingerprint = biometricHelper.isEnabled
    }

    private fun updateBalanceInLocalFormat() {
        val balanceInLocalFormat = selectedExchangeRate?.fiat?.let { fiat ->
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
            exchangeRate.coinToFiat(_uiState.value.balance).toFormattedString()
        } ?: ""

        _uiState.value = _uiState.value.copy(balanceInLocalFormat = balanceInLocalFormat)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun triggerWipe(afterWipeFunction: () -> Unit) {
        walletApplication.triggerWipe(afterWipeFunction)
    }

    fun setEnableFingerprint(enable: Boolean) {
        val isEnabled = enable && biometricHelper.isEnabled

        _uiState.value = _uiState.value.copy(fingerprintIsEnabled = isEnabled)

        if (configuration.enableFingerprint != isEnabled) {
            analytics.logEvent(
                if (isEnabled) {
                    AnalyticsConstants.Security.FINGERPRINT_ON
                } else {
                    AnalyticsConstants.Security.FINGERPRINT_OFF
                },
                mapOf()
            )

            configuration.enableFingerprint = isEnabled

            if (!isEnabled) {
                biometricHelper.clearBiometricInfo()
            }
        }
    }

    fun setHideBalanceOnLaunch(hide: Boolean) {
        viewModelScope.launch {
            walletUIConfig.set(WalletUIConfig.AUTO_HIDE_BALANCE, hide)
            analytics.logEvent(
                if (hide) {
                    AnalyticsConstants.Security.AUTOHIDE_BALANCE_ON
                } else {
                    AnalyticsConstants.Security.AUTOHIDE_BALANCE_OFF
                },
                mapOf()
            )
        }
    }

    suspend fun hasPendingTxMetadataToSave(): Boolean = withContext(Dispatchers.IO) {
        platformSyncService.hasPendingTxMetadataToSave()
    }
}
