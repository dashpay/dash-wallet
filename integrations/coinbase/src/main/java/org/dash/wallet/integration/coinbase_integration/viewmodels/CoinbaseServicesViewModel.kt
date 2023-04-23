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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountData
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val exchangeRatesProvider: ExchangeRatesProvider,
    private val preferences: Configuration,
    private val config: CoinbaseConfig,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    private val _user: MutableLiveData<CoinBaseUserAccountData> = MutableLiveData()
    val user: LiveData<CoinBaseUserAccountData>
        get() = _user

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _userAccountError: MutableLiveData<Boolean> = MutableLiveData()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    val coinbaseLogOutCallback = SingleLiveEvent<Unit>()

    private val _latestUserBalance: MutableLiveData<String> = MutableLiveData()
    val latestUserBalance: LiveData<String>
        get() = _latestUserBalance

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    val balanceFormat: MonetaryFormat
        get() = preferences.format.noCode()

    init {
        exchangeRatesProvider.observeExchangeRate(preferences.exchangeCurrencyCode!!)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
        getUserAccountInfo()
    }

    private fun getUserAccountInfo() = viewModelScope.launch(Dispatchers.Main) {
        val lastBalance = config.getPreference(CoinbaseConfig.LAST_BALANCE)

        if (lastBalance == null) {
            _showLoading.value = true
        } else {
            _latestUserBalance.value = Coin.valueOf(lastBalance).toPlainString()
        }
        when (val response = coinBaseRepository.getUserAccount()) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (response.value == null) {
                    _userAccountError.value = true
                } else {
                    _user.value = response.value
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
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
