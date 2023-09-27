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
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.coinbase.model.*
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CoinbaseBuyDashOrderReviewViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val walletDataProvider: WalletDataProvider,
    private val analyticsService: AnalyticsService,
    networkState: NetworkStateInt,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : ViewModel() {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitBuyOrderFailureState = SingleLiveEvent<String>()

    var sendFundToWalletParams: SendTransactionToWalletParams ? = null
    val placeBuyOrderFailedCallback = SingleLiveEvent<String>()
    private val _placeBuyOrder: MutableLiveData<PlaceBuyOrderUIModel> = MutableLiveData()
    val placeBuyOrder: LiveData<PlaceBuyOrderUIModel>
        get() = _placeBuyOrder
    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    val commitBuyOrderSuccessState = SingleLiveEvent<SendTransactionToWalletParams>()

    fun commitBuyOrder(params: String) = viewModelScope.launch(Dispatchers.Main) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_CONFIRM, mapOf())

        _showLoading.value = true
        when (val result = coinBaseRepository.commitBuyOrder(params)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == BuyOrderResponse.EMPTY_COMMIT_BUY) {
                    commitBuyOrderFailureState.call()
                } else {
                    sendFundToWalletParams = SendTransactionToWalletParams(
                        amount = result.value.dashAmount,
                        currency = result.value.dashCurrency,
                        idem = UUID.randomUUID().toString(),
                        to = walletDataProvider.freshReceiveAddress().toBase58(),
                        type = result.value.transactionType
                    ).apply {
                        commitBuyOrderSuccessState.value = this
                        transactionMetadataProvider.markAddressAsTransferInAsync(to!!, ServiceName.Coinbase)
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                val error = result.errorBody
                if (error.isNullOrEmpty()) {
                    commitBuyOrderFailureState.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        commitBuyOrderFailureState.call()
                    } else {
                        commitBuyOrderFailureState.value = message
                    }
                }
            }
        }
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
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

    fun onRefreshOrderClicked(fiat: Fiat?, paymentMethodId: String) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_RETRY, mapOf())
        placeBuyOrder(PlaceBuyOrderParams(fiat?.toPlainString(), fiat?.currencyCode, paymentMethodId))
    }
}
