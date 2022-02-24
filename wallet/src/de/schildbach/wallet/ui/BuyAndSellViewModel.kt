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

package de.schildbach.wallet.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import org.dash.wallet.integration.uphold.data.UpholdClient
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

/**
 * @author Eric Britten
 */
@HiltViewModel
class BuyAndSellViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepository,
    private val config: Configuration
) : ViewModel() {

    // TODO: move this into UpholdViewModel
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

    private val _coinbaseIsConnected: MutableLiveData<Boolean> = MutableLiveData()
    val coinbaseIsConnected: LiveData<Boolean>
        get() = _coinbaseIsConnected

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

    init {
        isUserConnectedToCoinbase()
    }

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

    fun isUserConnectedToCoinbase() {
        _coinbaseIsConnected.value = coinBaseRepository.isUserConnected()
    }

    private fun setDashServiceList(list: List<BuyAndSellDashServicesModel>) {
        _servicesList.value = list.sortedBy { it.serviceStatus }
        buyAndSellDashServicesModel = list
    }


    private fun changeItemStatus(isOnline: Boolean, clientIsAuthenticated: Boolean): BuyAndSellDashServicesModel.ServiceStatus {
        return if (clientIsAuthenticated){
            if (isOnline){
                BuyAndSellDashServicesModel.ServiceStatus.CONNECTED
            } else {
                BuyAndSellDashServicesModel.ServiceStatus.DISCONNECTED
            }
        } else BuyAndSellDashServicesModel.ServiceStatus.IDLE
    }

    fun setServicesStatus(isOnline: Boolean, coinBaseClientIsAuthenticated: Boolean, liquidClientIsAuthenticated: Boolean, upHoldClientIsAuthenticated: Boolean) {
        setDashServiceList(
            buyAndSellDashServicesModel.toMutableList().map { model ->
                val serviceStatus = when (model.serviceType) {
                    BuyAndSellDashServicesModel.ServiceType.LIQUID -> changeItemStatus(isOnline, liquidClientIsAuthenticated)
                    BuyAndSellDashServicesModel.ServiceType.UPHOLD -> changeItemStatus(isOnline, upHoldClientIsAuthenticated)
                    BuyAndSellDashServicesModel.ServiceType.COINBASE -> changeItemStatus(isOnline, coinBaseClientIsAuthenticated)
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
                        getUserCoinbaseBalance()
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

    private suspend fun getUserCoinbaseBalance() {
        when (val response = coinBaseRepository.getUserAccount()) {
            is ResponseResource.Success -> {
                val userAccountData = response.value
                //TODO: Handle use-case: failure to get user data and hence no balance to display in Buy & Sell Dash UI
                if (userAccountData == null){
                    _showLoading.value = false
                } else {
                    _coinbaseIsConnected.value = true
                    coinbaseAuthTokenCallback.call()
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }
}
