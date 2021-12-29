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
import android.app.Application
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountData
import org.dash.wallet.integration.coinbase_integration.model.CoinbasePaymentMethod
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepository,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    val config: Configuration
) : AndroidViewModel(application) {

    private val _user: MutableLiveData<CoinBaseUserAccountData> = MutableLiveData()
    val user: LiveData<CoinBaseUserAccountData>
        get() = _user

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _userAccountError: MutableLiveData<Boolean> = MutableLiveData()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    private val _userPaymentMethodsError: MutableLiveData<Boolean> = MutableLiveData()
    val userPaymentMethodsError: LiveData<Boolean>
        get() = _userPaymentMethodsError

    private val _userPaymentMethodsList: MutableLiveData<List<CoinbasePaymentMethod>> = MutableLiveData()
    val userPaymentMethodsList: LiveData<List<CoinbasePaymentMethod>>
        get() = _userPaymentMethodsList

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private fun getUserAccountInfo() = viewModelScope.launch {

        when (val response = coinBaseRepository.getUserAccount()) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                val userAccountData = response.value.body()?.data?.firstOrNull {
                    it.balance?.currency?.equals("DASH") ?: false
                }

                if (userAccountData == null) {
                    _userAccountError.value = true
                } else {
                    _user.value = userAccountData
                    coinBaseRepository.saveLastCoinbaseDashAccountBalance(userAccountData.balance?.amount)
                    coinBaseRepository.saveUserAccountId(userAccountData.id)
                }
            }
            is ResponseResource.Loading -> {
                _showLoading.value = true
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }

    fun disconnectCoinbaseAccount() {
        viewModelScope.launch {
            coinBaseRepository.disconnectCoinbaseAccount()
        }
    }

    fun getPaymentMethods(){
        viewModelScope.launch {

            when (val response = coinBaseRepository.getActivePaymentMethods()) {
                is ResponseResource.Success -> {
                    _showLoading.value = false
                    val userPaymentMethods = response.value.body()?.data?.map {
                        CoinbasePaymentMethod(
                            id = it.id,
                            type = it.type,
                            name = it.name,
                            currency= it.currency,
                            allowBuy = it.isBuyingAllowed,
                            allowSell = it.isSellingAllowed
                        )
                    }
                    if (userPaymentMethods == null||userPaymentMethods.isEmpty()) {
                        _userPaymentMethodsError.value = true
                    } else {
                        _userPaymentMethodsList.value = userPaymentMethods
                    }
                }
                is ResponseResource.Loading -> {
                    _showLoading.value = true
                }
                is ResponseResource.Failure -> {
                    _showLoading.value = false
                }
            }
        }
    }
    init {
        getUserAccountInfo()
        exchangeRatesProvider.observeExchangeRate(config.exchangeCurrencyCode)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
    }
}
