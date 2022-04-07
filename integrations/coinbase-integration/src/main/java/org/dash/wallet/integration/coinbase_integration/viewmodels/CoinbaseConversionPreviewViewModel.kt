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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.TRANSACTION_TYPE_SEND
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CoinbaseConversionPreviewViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val walletDataProvider: WalletDataProvider
) : ViewModel() {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitSwapTradeFailedCallback = SingleLiveEvent<Unit>()

    var sendFundToWalletParams: SendTransactionToWalletParams? = null

    private val _swapTradeOrder: MutableLiveData<SwapTradeUIModel> = MutableLiveData()
    val swapTradeOrder: LiveData<SwapTradeUIModel>
        get() = _swapTradeOrder

    val commitSwapTradeSuccessState = SingleLiveEvent<SendTransactionToWalletParams>()
    val sellSwapSuccessCallback = SingleLiveEvent<Unit>()
    val swapTradeFailedCallback = SingleLiveEvent<String>()

    fun commitSwapTrade(params: SwapTradeUIModel) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val result = coinBaseRepository.commitSwapTrade(params.swapTradeId)) {
            is ResponseResource.Success -> {
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    _showLoading.value = false
                    commitSwapTradeFailedCallback.call()
                } else {
                    if (params.inputCurrencyName == DASH_CURRENCY) {
                        sellSwapSuccessCallback.call()
                    }
                    else {
                        sendFundToWalletParams = SendTransactionToWalletParams(
                            amount = result.value.displayInputAmount,
                            currency = result.value.displayInputCurrency,
                            idem = UUID.randomUUID().toString(),
                            to = walletDataProvider.freshReceiveAddress().toBase58(),
                            type = TRANSACTION_TYPE_SEND
                        ).apply {
                            commitSwapTradeSuccessState.value = this
                        }
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                commitSwapTradeFailedCallback.call()
            }
        }
    }

    private fun swapTrade(swapTradeUIModel: SwapTradeUIModel) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        swapTradeUIModel.assetsBaseID?.let {
            val tradesRequest = TradesRequest(
                swapTradeUIModel.displayInputAmount,
                swapTradeUIModel.displayInputCurrency,
                source_asset = it.first,
                target_asset = it.second,
            )
            when (val result = coinBaseRepository.swapTrade(tradesRequest)) {
                is ResponseResource.Success -> {

                    if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                        _showLoading.value = false
                        swapTradeFailedCallback.call()
                    } else {
                        _showLoading.value = false

                        result.value.apply {
                            this.assetsBaseID = swapTradeUIModel.assetsBaseID
                            this.inputCurrencyName =
                                swapTradeUIModel.inputCurrencyName
                            this.outputCurrencyName = swapTradeUIModel.outputCurrencyName
                            _swapTradeOrder.value = this
                        }
                    }
                }
                is ResponseResource.Failure -> {
                    _showLoading.value = false

                    val error = result.errorBody?.string()
                    if (error.isNullOrEmpty()) {
                        swapTradeFailedCallback.call()
                    } else {
                        val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                        if (message.isNullOrEmpty()) {
                            swapTradeFailedCallback.call()
                        } else {
                            swapTradeFailedCallback.value = message
                        }
                    }
                }
            }
        }
    }

    fun onRefreshOrderClicked(swapTradeUIModel: SwapTradeUIModel) {
        swapTrade(swapTradeUIModel)
    }
}
