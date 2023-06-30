/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integration.uphold.ui

import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securepreferences.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integration.uphold.api.UpholdClient
import org.dash.wallet.integration.uphold.api.checkCapabilities
import org.dash.wallet.integration.uphold.api.getAccessToken
import org.dash.wallet.integration.uphold.api.getDashBalance
import org.dash.wallet.integration.uphold.api.isAuthenticated
import org.dash.wallet.integration.uphold.api.preferences
import org.dash.wallet.integration.uphold.api.revokeAccessToken
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.data.UpholdException
import org.slf4j.LoggerFactory
import retrofit2.HttpException
import javax.inject.Inject

data class UpholdPortalUIState(
    val balance: Coin = Coin.ZERO,
    val fiatBalance: Fiat? = null,
    val isUserLoggedIn: Boolean = false,
    val errorCode: Int? = null
)

@HiltViewModel
class UpholdViewModel @Inject constructor(
    private val upholdClient: UpholdClient,
    private val analytics: AnalyticsService,
    private val globalConfig: Configuration,
    exchangeRatesProvider: ExchangeRatesProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(UpholdViewModel::class.java)
    }

    private var exchangeRate: ExchangeRate? = null

    private val _uiState = MutableStateFlow(UpholdPortalUIState(isUserLoggedIn = upholdClient.isAuthenticated))
    val uiState: StateFlow<UpholdPortalUIState> = _uiState.asStateFlow()

    val balanceFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    init {
        globalConfig.lastUpholdBalance?.let { balance ->
            _uiState.update { it.copy(balance = Coin.parseCoin(balance)) }
        }

        exchangeRatesProvider
            .observeExchangeRate(globalConfig.exchangeCurrencyCode!!)
            .onEach { rate ->
                exchangeRate = ExchangeRate(Coin.COIN, rate.fiat)
                val fiatBalance = exchangeRate?.coinToFiat(_uiState.value.balance)
                _uiState.update { it.copy(fiatBalance = fiatBalance) }
            }
            .launchIn(viewModelScope)

        upholdClient.preferences.registerOnSharedPreferenceChangeListener { _, prefName ->
            if (prefName == SecurePreferences.hashPrefKey(UpholdClient.UPHOLD_ACCESS_TOKEN)) {
                _uiState.update { it.copy(isUserLoggedIn = upholdClient.isAuthenticated) }
            }
        }
    }

    suspend fun refreshBalance() {
        try {
            val balance = upholdClient.getDashBalance()
            globalConfig.lastUpholdBalance = balance.toString()
            val coin = balance.toCoin()
            val fiatBalance = exchangeRate?.coinToFiat(coin)
            _uiState.update { it.copy(balance = coin, fiatBalance = fiatBalance) }
        } catch (ex: Exception) {
            log.error("Error refreshing balance: ${ex.message}")

            if (ex is UpholdException) {
                if (ex.code == 401) {
                    _uiState.update { it.copy(isUserLoggedIn = false) }
                } else {
                    _uiState.update { it.copy(errorCode = ex.code) }
                }
            } else {
                _uiState.update { it.copy(errorCode = -1) }
            }
        }
    }

    suspend fun onAuthResult(code: String, state: String) {
        if (upholdClient.encryptionKey.equals(state)) {
            try {
                upholdClient.getAccessToken(code)
                _uiState.update { it.copy(isUserLoggedIn = true) }
                refreshBalance()
                checkCapabilities()
            } catch (ex: Exception) {
                log.error("Error obtaining Uphold access token: ${ex.message}")

                if (ex is HttpException) {
                    _uiState.update { it.copy(errorCode = ex.code()) }
                }
            }
        } else {
            log.error("Uphold state does not match the encryption key")
            _uiState.update { it.copy(errorCode = -1) }
        }
    }

    suspend fun checkCapabilities() {
        try {
            upholdClient.checkCapabilities()
        } catch (ex: Exception) {
            log.error("Error obtaining capabilities: " + ex.message)
        }
    }

    fun revokeUpholdAccessToken() {
        viewModelScope.launch {
            analytics.logEvent(AnalyticsConstants.Uphold.DISCONNECT, bundleOf())

            try {
                upholdClient.revokeAccessToken()
                _uiState.update { it.copy(isUserLoggedIn = false) }
            } catch (ex: Exception) {
                log.error("Error revoking Uphold access token: " + ex.message)
                val errorCode = if (ex is HttpException) ex.code() else -1

                if (errorCode == 401) {
                    _uiState.update { it.copy(isUserLoggedIn = false) }
                } else {
                    _uiState.update { it.copy(errorCode = errorCode) }
                }
            }
        }
    }

    fun getLinkAccountUrl(): String {
        return String.format(UpholdConstants.INITIAL_URL, upholdClient.encryptionKey)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, bundleOf())
    }

    fun errorHandled() {
        _uiState.update { it.copy(errorCode = null) }
    }
}
