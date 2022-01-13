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

import android.app.Application
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseAuthRepository
import org.dash.wallet.integration.uphold.data.UpholdClient
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

/**
 * @author Eric Britten
 */
@HiltViewModel
class BuyAndSellViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseAuthRepository,
    private val config: Configuration
) : AndroidViewModel(application) {

    // TODO: move this into UpholdViewModel
    private val triggerUploadBalanceUpdate = MutableLiveData<Unit>()

    fun updateUpholdBalance() {
        triggerUploadBalanceUpdate.value = Unit
    }

    private val upholdClient = UpholdClient.getInstance()

    private val _coinbaseIsConnected: MutableLiveData<Boolean> = MutableLiveData()
    val coinbaseIsConnected: LiveData<Boolean>
        get() = _coinbaseIsConnected

    private val _servicesList: MutableLiveData<List<BuyAndSellDashServicesModel>> = MutableLiveData()
    val servicesList: LiveData<List<BuyAndSellDashServicesModel>>
        get() = _servicesList

    private var buyAndSellDashServicesModel = BuyAndSellDashServicesModel.getBuyAndSellDashServicesList()
    val successfulCoinbaseLoginCallback = SingleLiveEvent<String>()

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
                        continuation.resumeWith(Result.success(Resource.error(e!!)))
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

    private fun changeCoinBaseItemStatus(isOnline: Boolean): BuyAndSellDashServicesModel.ServiceStatus {
        return if (coinBaseRepository.getUserLastCoinbaseBalance()== null) {
            BuyAndSellDashServicesModel.ServiceStatus.IDLE
        } else {
            if (coinBaseRepository.isUserConnected() && isOnline) {
                BuyAndSellDashServicesModel.ServiceStatus.CONNECTED
            } else {
                BuyAndSellDashServicesModel.ServiceStatus.DISCONNECTED
            }
        }
    }

    private fun changeItemStatus(isOnline: Boolean, clientIsAuthenticated: Boolean, userHadBalance: Boolean): BuyAndSellDashServicesModel.ServiceStatus {
        return if (!userHadBalance) {
            BuyAndSellDashServicesModel.ServiceStatus.IDLE
        } else {
            if (clientIsAuthenticated && isOnline) {
                BuyAndSellDashServicesModel.ServiceStatus.CONNECTED
            } else {
                BuyAndSellDashServicesModel.ServiceStatus.DISCONNECTED
            }
        }
    }

    fun setServicesStatus(isOnline: Boolean, liquidClientIsAuthenticated: Boolean, upHoldClientIsAuthenticated: Boolean) {
        setDashServiceList(
            buyAndSellDashServicesModel.toMutableList().map { model ->
                val serviceStatus = when (model.serviceType) {
                    BuyAndSellDashServicesModel.ServiceType.LIQUID -> changeItemStatus(isOnline, liquidClientIsAuthenticated, config.lastLiquidBalance.isNullOrEmpty().not())
                    BuyAndSellDashServicesModel.ServiceType.UPHOLD -> changeItemStatus(isOnline, upHoldClientIsAuthenticated, config.lastUpholdBalance.isNullOrEmpty().not())
                    BuyAndSellDashServicesModel.ServiceType.COINBASE -> changeCoinBaseItemStatus(isOnline)
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
        viewModelScope.launch {
            when (val response = coinBaseRepository.getUserToken(code)) {
                is ResponseResource.Success -> {
                    successfulCoinbaseLoginCallback.call()
                    _coinbaseIsConnected.value =
                        response.value.body()?.accessToken?.isEmpty()?.not()
                }
                is ResponseResource.Loading -> {
                }
                is ResponseResource.Failure -> {
                    _coinbaseIsConnected.value = false
                }
            }
        }
    }
}
