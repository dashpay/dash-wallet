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

import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountData
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val exchangeRatesProvider: ExchangeRatesProvider,
    val config: Configuration,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService
) : ConnectivityViewModel(networkState) {

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

    //val activePaymentMethodsFailureCallback = SingleLiveEvent<Unit>()
    val coinbaseLogOutCallback = SingleLiveEvent<Unit>()

    private val _latestUserBalance: MutableLiveData<String> = MutableLiveData()
    val latestUserBalance: LiveData<String>
        get() = _latestUserBalance

    init {
        exchangeRatesProvider.observeExchangeRate(config.exchangeCurrencyCode!!)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
        getUserAccountInfo()
    }

    private fun getUserAccountInfo() = viewModelScope.launch(Dispatchers.Main) {
        if(config.lastCoinbaseBalance.isNullOrEmpty()) {
            _showLoading.value = true
        }else{
            _latestUserBalance.value = config.lastCoinbaseBalance
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
        analyticsService.logEvent(AnalyticsConstants.Coinbase.DISCONNECT, bundleOf())

        _showLoading.value = true
        coinBaseRepository.disconnectCoinbaseAccount()
        _showLoading.value = false
        coinbaseLogOutCallback.call()
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, bundleOf())
    }
}