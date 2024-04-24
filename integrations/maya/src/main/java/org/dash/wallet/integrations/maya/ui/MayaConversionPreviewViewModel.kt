/*
 * Copyright 2024 Dash Core Group.
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
package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.MayaErrorResponse
import org.dash.wallet.integrations.maya.model.SwapTradeResponse
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.TradesRequest
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import javax.inject.Inject

@HiltViewModel
class MayaConversionPreviewViewModel @Inject constructor(
    private val mayaWebApi: MayaWebApi,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    private val analyticsService: AnalyticsService,
    networkState: NetworkStateInt,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : ViewModel() {
    lateinit var swapTradeUIModel: SwapTradeUIModel
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitSwapTradeFailureState = SingleLiveEvent<String>()

    private var sendFundToWalletParams: SendTransactionToWalletParams? = null

    private val _swapTradeOrder: MutableLiveData<SwapTradeUIModel> = MutableLiveData()
    val swapTradeOrder: LiveData<SwapTradeUIModel>
        get() = _swapTradeOrder

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    val commitSwapTradeSuccessState = SingleLiveEvent<SendTransactionToWalletParams>()
    val sellSwapSuccessState = SingleLiveEvent<Unit>()
    val swapTradeFailureState = SingleLiveEvent<String?>()

    val getUserAccountAddressFailedCallback = SingleLiveEvent<Unit>()
    val onFailure = SingleLiveEvent<String?>()
    val onInsufficientMoneyCallback = SingleLiveEvent<Unit>()

    var isFirstTime = true

    fun commitSwapTrade(tradeId: String) = viewModelScope.launch {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CONFIRM, mapOf())
        val inputCurrency = swapTradeUIModel.amount.dashCode
        val inputAmount = swapTradeUIModel.amount.dash

        // TODO: this is the action to do the swap
        // _showLoading.value = true
//        when (val result = coinBaseRepository.commitSwapTrade(tradeId)) {
//            is ResponseResource.Success -> {
//                _showLoading.value = false
//                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
//                    commitSwapTradeFailureState.call()
//                } else {
//                    if (inputCurrency == Constants.DASH_CURRENCY) {
//                        try {
//                            val coin = Coin.parseCoin(inputAmount)
//                            sellDashToCoinBase(coin)
//                        } catch (x: Exception) {
//                            Coin.ZERO
//                        }
//                    } else {
//                        sendFundToWalletParams = SendTransactionToWalletParams(
//                            amount = result.value.displayInputAmount,
//                            currency = result.value.displayInputCurrency,
//                            idem = UUID.randomUUID().toString(),
//                            to = walletDataProvider.freshReceiveAddress().toBase58(),
//                            type = MayaConstants.TRANSACTION_TYPE_SEND
//                        ).apply {
//                            commitSwapTradeSuccessState.value = this
//                            transactionMetadataProvider.markAddressAsTransferInAsync(to!!, ServiceName.Coinbase)
//                        }
//                    }
//                }
//            }
//            is ResponseResource.Failure -> {
//                _showLoading.value = false
//                val error = result.errorBody
//                if (error.isNullOrEmpty()) {
//                    commitSwapTradeFailureState.call()
//                } else {
//                    val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
//                    if (message.isNullOrEmpty()) {
//                        commitSwapTradeFailureState.call()
//                    } else {
//                        commitSwapTradeFailureState.value = message ?: ""
//                    }
//                }
//            }
//        }
    }

    fun swapTrade(swapTradeUIModel: SwapTradeUIModel) = viewModelScope.launch {
        _showLoading.value = true
        val tradesRequest = TradesRequest(
            swapTradeUIModel.amount,
            source_maya_asset = "DASH.DASH",
            target_maya_asset = swapTradeUIModel.outputAsset,
            fiatCurrency = swapTradeUIModel.amount.fiatCode,
            targetAddress = swapTradeUIModel.destinationAddress
        )
        when (val result = mayaWebApi.swapTrade(tradesRequest)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    swapTradeFailureState.call()
                } else {
                    result.value.apply {
                        this.inputCurrencyName =
                            swapTradeUIModel.inputCurrencyName
                        this.outputCurrencyName = swapTradeUIModel.outputCurrencyName
                    }
                    _swapTradeOrder.value = result.value
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false

                val error = result.errorBody
                if (error.isNullOrEmpty()) {
                    swapTradeFailureState.call()
                } else {
                    val message = MayaErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        swapTradeFailureState.call()
                    } else {
                        swapTradeFailureState.value = message
                    }
                }
            }
        }
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }
}
