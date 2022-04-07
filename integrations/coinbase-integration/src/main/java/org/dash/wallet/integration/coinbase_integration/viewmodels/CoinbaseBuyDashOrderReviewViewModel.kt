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
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class CoinbaseBuyDashOrderReviewViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val walletDataProvider: WalletDataProvider,
    val networkState: NetworkStateInt
) : ConnectivityViewModel(networkState) {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitBuyOrderFailedCallback = SingleLiveEvent<String>()

    var sendFundToWalletParams: SendTransactionToWalletParams ? = null
    val placeBuyOrderFailedCallback = SingleLiveEvent<String>()
    private val _placeBuyOrder: MutableLiveData<PlaceBuyOrderUIModel> = MutableLiveData()
    val placeBuyOrder: LiveData<PlaceBuyOrderUIModel>
        get() = _placeBuyOrder

    val commitBuyOrderSuccessState = SingleLiveEvent<SendTransactionToWalletParams>()

    fun commitBuyOrder(params: String) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val result = coinBaseRepository.commitBuyOrder(params)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == BuyOrderResponse.EMPTY_COMMIT_BUY) {
                    commitBuyOrderFailedCallback.call()
                } else {
                    sendFundToWalletParams = SendTransactionToWalletParams(
                        amount = result.value.dashAmount,
                        currency = result.value.dashCurrency,
                        idem = UUID.randomUUID().toString(),
                        to = walletDataProvider.freshReceiveAddress().toBase58(),
                        type = result.value.transactionType
                    ).apply {
                        commitBuyOrderSuccessState.value = this
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                val error = result.errorBody?.string()
                if (error.isNullOrEmpty()) {
                    commitBuyOrderFailedCallback.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        commitBuyOrderFailedCallback.call()
                    } else {
                        commitBuyOrderFailedCallback.value = message
                    }
                }
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

    fun onRefreshOrderClicked(fiat: Fiat?, paymentMethodId: String) {
        placeBuyOrder(PlaceBuyOrderParams(fiat?.toPlainString(), fiat?.currencyCode, paymentMethodId))
    }
}
