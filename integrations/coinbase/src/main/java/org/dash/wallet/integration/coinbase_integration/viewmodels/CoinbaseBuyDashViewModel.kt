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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
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
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.lang.NumberFormatException
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class CoinbaseBuyDashViewModel @Inject constructor(private val coinBaseRepository: CoinBaseRepositoryInt,
                                                   private val userPreference: Configuration,
                                                   var exchangeRates: ExchangeRatesProvider,
                                                   var networkState: NetworkStateInt,
                                                   private val analyticsService: AnalyticsService
) : ConnectivityViewModel(networkState) {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _activePaymentMethods: MutableLiveData<List<PaymentMethod>> = MutableLiveData()
    val activePaymentMethods: LiveData<List<PaymentMethod>>
        get() = _activePaymentMethods

    private val _placeBuyOrder: MutableLiveData<Event<PlaceBuyOrderUIModel>> = MutableLiveData()
    val placeBuyOrder: LiveData<Event<PlaceBuyOrderUIModel>>
        get() = _placeBuyOrder

    val placeBuyOrderFailedCallback = SingleLiveEvent<String>()
     var exchangeRate: ExchangeRate?= null

    init {
        getWithdrawalLimit()
    }

    fun onContinueClicked(
        dashToFiat: Boolean,
        fiat: Fiat,
        dashAmount: CharSequence,
        paymentMethodIndex: Int
    ) {
        _activePaymentMethods.value?.let {
            if (paymentMethodIndex < it.size) {
                val paymentMethod = it[paymentMethodIndex]

                analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_CONTINUE, bundleOf())
                analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_PAYMENT_METHOD, bundleOf(
                    AnalyticsConstants.Parameters.VALUE to paymentMethod.paymentMethodType.name
                ))
                analyticsService.logEvent(
                    if (dashToFiat) AnalyticsConstants.Coinbase.BUY_ENTER_DASH
                    else AnalyticsConstants.Coinbase.BUY_ENTER_FIAT,
                    bundleOf()
                )

                viewModelScope.launch {
                    placeBuyOrder(PlaceBuyOrderParams(dashAmount.toString(), Constants.DASH_CURRENCY, paymentMethod.paymentMethodId))
                }
            }
        }
    }

    private suspend fun placeBuyOrder(params: PlaceBuyOrderParams) {
        _showLoading.value = true
        when (val result = coinBaseRepository.placeBuyOrder(params)) {
            is ResponseResource.Success -> {
                if (result.value == BuyOrderResponse.EMPTY_PLACE_BUY) {
                    _showLoading.value = false
                    placeBuyOrderFailedCallback.call()
                } else {
                    _showLoading.value = false
                    _placeBuyOrder.value = Event(result.value)
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false

                val error = result.errorBody
                if (error.isNullOrEmpty()) {
                    placeBuyOrderFailedCallback.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        placeBuyOrderFailedCallback.call()
                    } else {
                        placeBuyOrderFailedCallback.value = message
                    }
                }
            }
        }
    }

    fun setActivePaymentMethods(coinbasePaymentMethods: Array<PaymentMethod>) {
        _activePaymentMethods.value = coinbasePaymentMethods.toList()
    }

    private fun getWithdrawalLimit() = viewModelScope.launch(Dispatchers.Main){
        when (val response = coinBaseRepository.getWithdrawalLimit()){
            is ResponseResource.Success -> {
                val withdrawalLimit = response.value
                exchangeRate = getCurrencyExchangeRate(withdrawalLimit.currency)
            }
            is ResponseResource.Failure -> {
                //todo use case when limit is not fetched
            }
        }
    }

    fun isInputGreaterThanLimit(amountInDash: Coin): Boolean {
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(withdrawalLimitInDash) > 0
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, bundleOf())
    }

    private val withdrawalLimitInDash: Double
        get() {
            return if (userPreference.coinbaseUserWithdrawalLimitAmount.isNullOrEmpty()){
                0.0
            } else {
                val formattedAmount = GenericUtils.formatFiatWithoutComma(userPreference.coinbaseUserWithdrawalLimitAmount)
                val fiatAmount = try {
                    Fiat.parseFiat(userPreference.coinbaseSendLimitCurrency, formattedAmount)
                }catch (x: Exception) {
                    Fiat.valueOf(userPreference.coinbaseSendLimitCurrency, 0)
                }
                if( exchangeRate?.fiat!=null) {
                    val newRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRate?.fiat)
                    val amountInDash = newRate.fiatToCoin(fiatAmount)
                    amountInDash.toPlainString().toDoubleOrZero
                }else{
                    0.0
                }
            }
        }

    private suspend fun getCurrencyExchangeRate(currency: String): ExchangeRate {
        return exchangeRates.observeExchangeRate(currency).first()
    }
}

val String.toDoubleOrZero: Double
 get() = try {
     this.toDouble()
 }catch (e: NumberFormatException){
     0.0
 }
