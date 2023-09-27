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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.model.*
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import java.lang.NumberFormatException
import javax.inject.Inject

@HiltViewModel
class CoinbaseBuyDashViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val config: CoinbaseConfig,
    var exchangeRates: ExchangeRatesProvider,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _activePaymentMethods: MutableLiveData<List<PaymentMethod>> = MutableLiveData()
    val activePaymentMethods: LiveData<List<PaymentMethod>>
        get() = _activePaymentMethods

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    val placeBuyOrder = SingleLiveEvent<PlaceBuyOrderUIModel>()
    val placeBuyOrderFailedCallback = SingleLiveEvent<String>()

    var exchangeRate: ExchangeRate? = null
        private set

    init {
        getWithdrawalLimit()
    }

    fun onContinueClicked(
        dashToFiat: Boolean,
        dashAmount: CharSequence,
        paymentMethodIndex: Int
    ) {
        _activePaymentMethods.value?.let {
            if (paymentMethodIndex < it.size) {
                val paymentMethod = it[paymentMethodIndex]

                analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_CONTINUE, mapOf())
                analyticsService.logEvent(
                    AnalyticsConstants.Coinbase.BUY_PAYMENT_METHOD,
                    mapOf(
                        AnalyticsConstants.Parameter.VALUE to paymentMethod.paymentMethodType.name
                    )
                )
                analyticsService.logEvent(
                    if (dashToFiat) {
                        AnalyticsConstants.Coinbase.BUY_ENTER_DASH
                    } else {
                        AnalyticsConstants.Coinbase.BUY_ENTER_FIAT
                    },
                    mapOf()
                )

                viewModelScope.launch {
                    placeBuyOrder(
                        PlaceBuyOrderParams(
                            dashAmount.toString(),
                            Constants.DASH_CURRENCY,
                            paymentMethod.paymentMethodId
                        )
                    )
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
                    placeBuyOrder.value = result.value
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

    private fun getWithdrawalLimit() = viewModelScope.launch(Dispatchers.Main) {
        when (val response = coinBaseRepository.getWithdrawalLimit()) {
            is ResponseResource.Success -> {
                val withdrawalLimit = response.value
                exchangeRate = getCurrencyExchangeRate(withdrawalLimit.currency)
            }
            is ResponseResource.Failure -> {
                // todo use case when limit is not fetched
            }
        }
    }

    suspend fun isInputGreaterThanLimit(amountInDash: Coin): Boolean {
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(getWithdrawalLimitInDash()) > 0
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    private suspend fun getWithdrawalLimitInDash(): Double {
        val withdrawalLimit = config.get(CoinbaseConfig.USER_WITHDRAWAL_LIMIT)
        return if (withdrawalLimit.isNullOrEmpty()) {
            0.0
        } else {
            val formattedAmount = GenericUtils.formatFiatWithoutComma(withdrawalLimit)
            val currency = config.get(CoinbaseConfig.SEND_LIMIT_CURRENCY) ?: CoinbaseConstants.DEFAULT_CURRENCY_USD
            val fiatAmount = try {
                Fiat.parseFiat(currency, formattedAmount)
            } catch (x: Exception) {
                Fiat.valueOf(currency, 0)
            }
            if (exchangeRate?.fiat != null) {
                val newRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRate?.fiat)
                val amountInDash = newRate.fiatToCoin(fiatAmount)
                amountInDash.toPlainString().toDoubleOrZero
            } else {
                0.0
            }
        }
    }

    private suspend fun getCurrencyExchangeRate(currency: String): ExchangeRate? {
        return exchangeRates.observeExchangeRate(currency).first()
    }
}

val String.toDoubleOrZero: Double
    get() = try {
        this.toDouble()
    } catch (e: NumberFormatException) {
        0.0
    }
