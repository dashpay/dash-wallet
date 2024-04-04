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
package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import javax.inject.Inject

@HiltViewModel
class MayaConversionPreviewViewModel @Inject constructor(
    private val coinBaseRepository: MayaWebApi,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    private val analyticsService: AnalyticsService,
    networkState: NetworkStateInt,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : ViewModel() {
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

    fun commitSwapTrade(tradeId: String, inputCurrency: String, inputAmount: String) = viewModelScope.launch {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CONFIRM, mapOf())

        _showLoading.value = true
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

    private fun swapTrade(swapTradeUIModel: SwapTradeUIModel) = viewModelScope.launch {
//        _showLoading.value = true
//        swapTradeUIModel.assetsBaseID?.let {
//            val tradesRequest = TradesRequest(
//                swapTradeUIModel.amount,
//                swapTradeUIModel.displayInputAmount,
//                swapTradeUIModel.displayInputCurrency,
//                source_asset = it.first,
//                target_asset = it.second,
//                fiatCurrency = swapTradeUIModel.feeCurrency
//            )
//            when (val result = coinBaseRepository.swapTrade(tradesRequest)) {
//                is ResponseResource.Success -> {
//                    _showLoading.value = false
//                    if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
//                        swapTradeFailureState.call()
//                    } else {
//                        result.value.apply {
//                            this.assetsBaseID = swapTradeUIModel.assetsBaseID
//                            this.inputCurrencyName =
//                                swapTradeUIModel.inputCurrencyName
//                            this.outputCurrencyName = swapTradeUIModel.outputCurrencyName
//                        }
//                        _swapTradeOrder.value = result.value
//                    }
//                }
//                is ResponseResource.Failure -> {
//                    _showLoading.value = false
//
//                    val error = result.errorBody
//                    if (error.isNullOrEmpty()) {
//                        swapTradeFailureState.call()
//                    } else {
//                        val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
//                        if (message.isNullOrEmpty()) {
//                            swapTradeFailureState.call()
//                        } else {
//                            swapTradeFailureState.value = message
//                        }
//                    }
//                }
//            }
//        }
    }

    fun onRefreshOrderClicked(swapTradeUIModel: SwapTradeUIModel) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_RETRY, mapOf())
        swapTrade(swapTradeUIModel)
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

//    private suspend fun sellDashToCoinBase(coin: Coin) {
//        _showLoading.value = true
//
//        when (val result = coinBaseRepository.createAddress()) {
//            is ResponseResource.Success -> {
//                if (result.value.isEmpty()) {
//                    _showLoading.value = false
//                    getUserAccountAddressFailedCallback.call()
//                } else {
//                    sendDashToCoinbase(coin, result.value)
//                    sellSwapSuccessState.call()
//                    _showLoading.value = false
//                }
//            }
//            is ResponseResource.Failure -> {
//                _showLoading.value = false
//                getUserAccountAddressFailedCallback.call()
//            }
//        }
//    }

    private suspend fun sendDashToCoinbase(coin: Coin, addressInfo: String): Boolean {
        val address = Address.fromString(walletDataProvider.networkParameters, addressInfo.trim { it <= ' ' })
        return try {
            val transaction = sendPaymentService.sendCoins(address, coin, checkBalanceConditions = false)
            transactionMetadataProvider.markAddressAsTransferOutAsync(
                address.toBase58(),
                ServiceName.Coinbase
            )
            transaction.isPending
        } catch (x: InsufficientMoneyException) {
            onInsufficientMoneyCallback.call()
            x.printStackTrace()
            false
        } catch (ex: Exception) {
            onFailure.value = ex.message
            ex.printStackTrace()
            false
        }
    }
}
