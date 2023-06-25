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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integration.uphold.api.UpholdClient
import org.dash.wallet.integration.uphold.api.checkCapabilities
import org.dash.wallet.integration.uphold.api.getDashBalance
import org.dash.wallet.integration.uphold.api.isAuthenticated
import org.dash.wallet.integration.uphold.api.preferences
import org.dash.wallet.integration.uphold.api.revokeAccessToken
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.data.UpholdException
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class UpholdPortalUIState(
    val balance: Coin = Coin.ZERO,
    val fiatBalance: Fiat? = null,
    val isUserLoggedIn: Boolean = true,
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
            if (prefName == UpholdClient.UPHOLD_ACCESS_TOKEN) {
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
            if (ex is UpholdException) {
                if (ex.code == 401) {
                    // we don't have the correct access token
                    _uiState.update { it.copy(isUserLoggedIn = false) }
                } else {
                    _uiState.update { it.copy(errorCode = ex.code) }
                }
            } else {
                _uiState.update { it.copy(errorCode = -1) }
            }
        }
    }

    suspend fun checkCapabilities() {
        try {
            upholdClient.checkCapabilities()
        } catch (ex: Exception) {
            log.error("Error obtaining capabilities: " + ex.message)
        }
    }

    suspend fun revokeUpholdAccessToken() {
        analytics.logEvent(AnalyticsConstants.Uphold.DISCONNECT, bundleOf())

        try {
            upholdClient.revokeAccessToken()
        } catch (ex: Exception) {
            log.error("Error revoking Uphold access token: " + ex.message)
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
