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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseBuyDashViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepository,
    val config: Configuration
) : AndroidViewModel(application) {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _activePaymentMethods: MutableLiveData<List<PaymentMethod>> = MutableLiveData()
    val activePaymentMethods: LiveData<List<PaymentMethod>>
        get() = _activePaymentMethods

    private val _placeBuyOrder: MutableLiveData<PlaceBuyOrderUIModel> = MutableLiveData()
    val placeBuyOrder: LiveData<PlaceBuyOrderUIModel>
        get() = _placeBuyOrder

    val placeBuyOrderFailedCallback = SingleLiveEvent<String>()

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

                    _placeBuyOrder.value = result.value
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
                        placeBuyOrderFailedCallback.value = message
                    }
                }
            }
        }
    }

    fun getActivePaymentMethods(coinbasePaymentMethods: Array<PaymentMethod>) {
        _activePaymentMethods.value = coinbasePaymentMethods.toList()
    }
}
