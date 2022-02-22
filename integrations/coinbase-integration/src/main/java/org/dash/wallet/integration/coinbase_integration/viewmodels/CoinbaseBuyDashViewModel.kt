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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import java.lang.NumberFormatException
import javax.inject.Inject

@HiltViewModel
class CoinbaseBuyDashViewModel @Inject constructor(private val coinBaseRepository: CoinBaseRepository,
                                                   private val userPreference: Configuration,
                                                   var exchangeRates: ExchangeRatesProvider
) : ViewModel() {
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
    lateinit var exchangeRate: ExchangeRate
    private var inputAmountInDash: Coin = Coin.ZERO
    fun onContinueClicked(fiat: Fiat, paymentMethodIndex: Int) {
        _activePaymentMethods.value?.let {
            if (paymentMethodIndex < it.size) {
                val paymentMethod = it[paymentMethodIndex]
                placeBuyOrder(PlaceBuyOrderParams(fiat.toPlainString(), fiat.currencyCode, paymentMethod.paymentMethodId))
            }
        }
    }

    private fun placeBuyOrder(params: PlaceBuyOrderParams) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val result = coinBaseRepository.placeBuyOrder(params)) {
            is ResponseResource.Success -> {
                if (result.value == BuyOrderResponse.EMPTY_PLACE_BUY) {
                    _showLoading.value = false
                    placeBuyOrderFailedCallback.call()
                } else {
                    _showLoading.value = false
                    _placeBuyOrder.value = Event(result.value)
                    userPreference.coinbaseUserInputAmount = inputAmountInDash.toPlainString().toDoubleOrZero.toString()
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false

                val error = result.errorBody?.string()
                if (error.isNullOrEmpty()) {
                    placeBuyOrderFailedCallback.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)
                    if (message.isNullOrEmpty()) {
                        placeBuyOrderFailedCallback.call()
                    } else {
                        placeBuyOrderFailedCallback.value = message!!
                    }
                }
            }
        }
    }

    fun getActivePaymentMethods(coinbasePaymentMethods: Array<PaymentMethod>) {
        _activePaymentMethods.value = coinbasePaymentMethods.toList()
    }

    init {
        getWithdrawalLimit()
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
        inputAmountInDash = amountInDash
        return inputAmountInDash.toPlainString().toDoubleOrZero.minus(withdrawalLimitInDash()) > 0
    }

    private fun withdrawalLimitInDash(): Double {
        return if (userPreference.coinbaseUserWithdrawalRemaining.isNullOrEmpty()){
            0.0
        } else {
            val formattedAmount = GenericUtils.formatFiatWithoutComma(userPreference.coinbaseUserWithdrawalRemaining)
            val fiatAmount = Fiat.parseFiat(userPreference.coinbaseSendLimitCurrency, formattedAmount)
            val newRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRate.fiat)
            val amountInDash = newRate.fiatToCoin(fiatAmount)
            amountInDash.toPlainString().toDoubleOrZero
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
