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

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorType
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class CoinbaseServicesUIState(
    val balance: Coin = Coin.ZERO,
    val balanceFiat: Fiat? = null,
    val isBalanceUpdating: Boolean = false,
    val isLoggedIn: Boolean = true,
    val error: CoinbaseErrorType = CoinbaseErrorType.NONE
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val exchangeRatesProvider: ExchangeRatesProvider,
    private val preferences: Configuration,
    private val config: CoinbaseConfig,
    private val walletUIConfig: WalletUIConfig,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(CoinbaseServicesViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(
        CoinbaseServicesUIState(isLoggedIn = coinBaseRepository.isAuthenticated)
    )
    val uiState: StateFlow<CoinbaseServicesUIState> = _uiState.asStateFlow()

    val balanceFormat: MonetaryFormat
        get() = preferences.format.noCode()

    init {
        config.observe(CoinbaseConfig.LAST_BALANCE)
            .map { uiState.value.copy(balance = Coin.valueOf(it ?: 0)) }
            .filterNotNull()
            .flatMapLatest { state ->
                walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
                    .filterNotNull()
                    .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
                    .map { exchangeRate ->
                        val fiatBalance = exchangeRate?.let {
                            val rate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRate.fiat)
                            rate.coinToFiat(state.balance)
                        }
                        state.copy(balanceFiat = fiatBalance)
                    }
            }.onEach { state -> _uiState.value = state }
            .launchIn(viewModelScope)

        // TODO: disabled until Coinbase changes are clear
//        viewModelScope.launch { coinBaseRepository.refreshWithdrawalLimit() }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isBalanceUpdating = true)
                val response = coinBaseRepository.getUserAccount()
                config.set(
                    CoinbaseConfig.LAST_BALANCE,
                    response.coinBalance().value
                )
            } catch (ex: IllegalStateException) {
                _uiState.value = _uiState.value.copy(error = CoinbaseErrorType.USER_ACCOUNT_ERROR)
            } catch (ex: Exception) {
                log.error("Error refreshing Coinbase balance", ex)
            } finally {
                _uiState.value = _uiState.value.copy(isBalanceUpdating = false)
            }
        }
    }

    fun disconnectCoinbaseAccount() = viewModelScope.launch {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.DISCONNECT, mapOf())
        coinBaseRepository.disconnectCoinbaseAccount()
        _uiState.value = _uiState.value.copy(isLoggedIn = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = CoinbaseErrorType.NONE)
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }
}
