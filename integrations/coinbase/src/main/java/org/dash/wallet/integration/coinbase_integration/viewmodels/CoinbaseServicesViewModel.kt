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
package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.unwrap
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.BalanceUIState
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val exchangeRatesProvider: ExchangeRatesProvider,
    private val preferences: Configuration,
    private val config: CoinbaseConfig,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(CoinbaseServicesViewModel::class.java)
    }

    private val _balanceUIState: MutableLiveData<BalanceUIState> = MutableLiveData(BalanceUIState())
    val balanceUIState: LiveData<BalanceUIState>
        get() = _balanceUIState

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _userAccountError: MutableLiveData<Boolean> = MutableLiveData()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    val coinbaseLogOutCallback = SingleLiveEvent<Unit>()

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    val balanceFormat: MonetaryFormat
        get() = preferences.format.noCode()

    init {
        config.observe(CoinbaseConfig.LAST_BALANCE)
            .map { _balanceUIState.value?.copy(balance = Coin.valueOf(it ?: 0)) }
            .filterNotNull()
            .flatMapLatest { state ->
                exchangeRatesProvider.observeExchangeRate(preferences.exchangeCurrencyCode!!)
                    .map { exchangeRate ->
                        val fiatBalance = exchangeRate?.let {
                            val rate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRate.fiat)
                            rate.coinToFiat(state.balance)
                        }
                        state.copy(balanceFiat = fiatBalance)
                    }
            }.onEach { state -> _balanceUIState.value = state }
            .launchIn(viewModelScope)
    }

    fun refreshBalance() {
        viewModelScope.launch {
            try {
                _balanceUIState.value = _balanceUIState.value?.copy(isUpdating = true)
                val response = coinBaseRepository.getUserAccount().unwrap()
                config.set(
                    CoinbaseConfig.LAST_BALANCE,
                    Coin.parseCoin(response?.balance?.amount ?: "0.0").value
                )
            } catch (ex: Exception) {
                log.error("Error refreshing Coinbase balance", ex)
            } finally {
                _balanceUIState.value = _balanceUIState.value?.copy(isUpdating = false)
            }
        }
    }

    fun disconnectCoinbaseAccount() = viewModelScope.launch(Dispatchers.Main) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.DISCONNECT, mapOf())

        _showLoading.value = true
        coinBaseRepository.disconnectCoinbaseAccount()
        _showLoading.value = false
        coinbaseLogOutCallback.call()
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }
}
