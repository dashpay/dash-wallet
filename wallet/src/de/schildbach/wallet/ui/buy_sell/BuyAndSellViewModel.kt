/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.buy_sell

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import org.dash.wallet.integration.uphold.api.UpholdClient
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

/**
 * @author Eric Britten
 */
@ExperimentalCoroutinesApi
@HiltViewModel
class BuyAndSellViewModel
@Inject constructor(
    private val coinBaseRepository: CoinBaseRepository,
    private val config: Configuration,
    networkState: NetworkStateInt
): ConnectivityViewModel(networkState) {

    //TODO: move this into UpholdViewModel
    private val triggerUploadBalanceUpdate = MutableLiveData<Unit>()

    fun updateUpholdBalance() {
        triggerUploadBalanceUpdate.value = Unit
    }

    private val upholdClient = UpholdClient.getInstance()

    var shouldShowAuthInfoPopup: Boolean
        get() = !config.hasCoinbaseAuthInfoBeenShown
        set(value) {
            config.hasCoinbaseAuthInfoBeenShown = !value
        }

    private val _isAuthenticatedOnCoinbase: MutableLiveData<Boolean> = MutableLiveData()
    val isAuthenticatedOnCoinbase: LiveData<Boolean>
        get() = _isAuthenticatedOnCoinbase

    private val _coinbaseBalance: MutableLiveData<String> = MutableLiveData()
    val coinbaseBalance: LiveData<String>
        get() = _coinbaseBalance

    private val _servicesList: MutableLiveData<List<BuyAndSellDashServicesModel>> = MutableLiveData()
    val servicesList: LiveData<List<BuyAndSellDashServicesModel>>
        get() = _servicesList

    private var buyAndSellDashServicesModel = BuyAndSellDashServicesModel.getBuyAndSellDashServicesList()

    private var _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    fun setLoadingState(show: Boolean){
        _showLoading.value = show
    }

    val coinbaseAuthTokenCallback = SingleLiveEvent<Boolean>()

    val upholdBalanceLiveData = Transformations.switchMap(triggerUploadBalanceUpdate) {
        liveData {
            emit(Resource.loading())
            val result = suspendCoroutine<Resource<BigDecimal>> { continuation ->
                upholdClient.getDashBalance(object : UpholdClient.Callback<BigDecimal> {
                    override fun onSuccess(data: BigDecimal) {
                        continuation.resumeWith(Result.success(Resource.success(data)))
                    }

                    override fun onError(e: java.lang.Exception, otpRequired: Boolean) {
                        continuation.resumeWith(Result.success(Resource.error(e)))
                    }
                })
            }
            emit(result)
        }
    }

    fun isUserConnectedToCoinbase(): Boolean = coinBaseRepository.isUserConnected()

    private fun setDashServiceList(list: List<BuyAndSellDashServicesModel>) {
        _servicesList.value = list.sortedBy { it.serviceStatus }
        buyAndSellDashServicesModel = list
    }


    private fun changeItemStatus(clientIsAuthenticated: Boolean): BuyAndSellDashServicesModel.ServiceStatus {
        return if (clientIsAuthenticated){
            if (isDeviceConnectedToInternet.value == true){
                BuyAndSellDashServicesModel.ServiceStatus.CONNECTED
            } else {
                BuyAndSellDashServicesModel.ServiceStatus.DISCONNECTED
            }
        } else BuyAndSellDashServicesModel.ServiceStatus.IDLE
    }

    fun setServicesStatus(coinBaseClientIsAuthenticated: Boolean, upHoldClientIsAuthenticated: Boolean) {
        setDashServiceList(
            buyAndSellDashServicesModel.toMutableList().map { model ->
                val serviceStatus = when (model.serviceType) {
                    BuyAndSellDashServicesModel.ServiceType.UPHOLD -> changeItemStatus(upHoldClientIsAuthenticated)
                    BuyAndSellDashServicesModel.ServiceType.COINBASE -> changeItemStatus(coinBaseClientIsAuthenticated)
                }
                if (serviceStatus != model.serviceStatus) {
                    model.copy(serviceStatus = serviceStatus)
                } else {
                    model
                }
            }
        )
    }

    fun showRowBalance(serviceType: BuyAndSellDashServicesModel.ServiceType, currentExchangeRate: org.dash.wallet.common.data.ExchangeRate?, amount: String) {
        val list = buyAndSellDashServicesModel.toMutableList().map { model ->
            if (model.serviceType == serviceType) {
                val balance = try {
                    Coin.parseCoin(amount)
                } catch (x: Exception) {
                    Coin.ZERO
                }
                if (currentExchangeRate == null) {
                    model.copy(balance = balance)
                } else {
                    val exchangeRate = ExchangeRate(Coin.COIN, currentExchangeRate.fiat)
                    val localValue = exchangeRate.coinToFiat(balance)
                    model.copy(balance = balance, localBalance = localValue)
                }
            } else {
                model
            }
        }
        setDashServiceList(list)
    }

    fun getUserLastCoinBaseAccountBalance() = coinBaseRepository.getUserLastCoinbaseBalance()

    fun loginToCoinbase(code: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _showLoading.value = true
            when (val response = coinBaseRepository.completeCoinbaseAuthentication(code)) {
                is ResponseResource.Success -> {
                    if (response.value){
                        _isAuthenticatedOnCoinbase.value = true
                        _coinbaseBalance.value = config.lastCoinbaseBalance
                        coinbaseAuthTokenCallback.call()
                    } else {
                        _showLoading.value = false
                    }
                }

                is ResponseResource.Failure -> { //TODO If login failed, inform the user
                    _showLoading.value = false
                }
            }
        }
    }

    fun updateCoinbaseBalance() {
        viewModelScope.launch(Dispatchers.Main){
            when (val response = coinBaseRepository.getUserAccount()) {
                is ResponseResource.Success -> {
                    _coinbaseBalance.value = response.value?.balance?.amount
                }
                is ResponseResource.Failure -> {
                    _coinbaseBalance.value = if (!config.lastCoinbaseBalance.isNullOrEmpty()) {
                            config.lastCoinbaseBalance
                        } else "0.0"
                    }
                }
            }
        }
}
