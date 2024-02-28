/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integrations.coinbase.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.ui.convert_currency.model.BaseIdForFiatData
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import java.lang.Exception
import javax.inject.Inject

data class CoinbaseUIState(
    val baseIdForFiatModel: BaseIdForFiatData = BaseIdForFiatData.LoadingState,
    val isSessionExpired: Boolean = false,
    val isNetworkAvailable: Boolean = false
)

@HiltViewModel
class CoinbaseViewModel @Inject constructor(
    private val config: CoinbaseConfig,
    private val walletUIConfig: WalletUIConfig,
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val analytics: AnalyticsService,
    networkState: NetworkStateInt
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(CoinbaseViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(CoinbaseUIState())
    val uiState: StateFlow<CoinbaseUIState> = _uiState.asStateFlow()

    init {
        config.observe(CoinbaseConfig.LAST_REFRESH_TOKEN)
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { token ->
                _uiState.update { it.copy(isSessionExpired = token.isEmpty()) }
            }
            .launchIn(viewModelScope)

        networkState.isConnected
            .onEach { isConnected ->
                _uiState.update { it.copy(isNetworkAvailable = isConnected) }
            }
            .launchIn(viewModelScope)
    }

    suspend fun loginToCoinbase(code: String): Boolean {
        return try {
            coinBaseRepository.completeCoinbaseAuthentication(code)
        } catch (ex: Exception) {
            log.error("Coinbase login error $ex")
            false
        }
    }
    fun getBaseIdForFiatModel() = viewModelScope.launch {
        _uiState.update { it.copy(baseIdForFiatModel = BaseIdForFiatData.LoadingState) }

        when (
            val response = coinBaseRepository.getBaseIdForUSDModel(
                walletUIConfig.getExchangeCurrencyCode()
            )
        ) {
            is ResponseResource.Success -> {
                _uiState.update {
                    it.copy(
                        baseIdForFiatModel = if (response.value?.data != null) {
                            BaseIdForFiatData.Success(response.value?.data!!)
                        } else {
                            BaseIdForFiatData.LoadingState
                        }
                    )
                }
            }

            is ResponseResource.Failure -> {
                runBlocking { config.set(CoinbaseConfig.UPDATE_BASE_IDS, true) }
                _uiState.update { it.copy(baseIdForFiatModel = BaseIdForFiatData.Error) }
            }
            else -> { }
        }
    }

    fun clearWasLoggedOut() {
        _uiState.update { it.copy(isSessionExpired = false) }
    }

    suspend fun isInputGreaterThanLimit(amountInDash: Coin): Boolean {
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(
            coinBaseRepository.getWithdrawalLimitInDash()
        ) > 0
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }
}
