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

import android.app.Service
import androidx.core.os.bundleOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class CoinbaseConversionPreviewViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    val networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : ConnectivityViewModel(networkState) {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitSwapTradeFailureState = SingleLiveEvent<String>()

    private var sendFundToWalletParams: SendTransactionToWalletParams? = null

    private val _swapTradeOrder: MutableLiveData<SwapTradeUIModel> = MutableLiveData()
    val swapTradeOrder: LiveData<SwapTradeUIModel>
        get() = _swapTradeOrder

    val commitSwapTradeSuccessState = SingleLiveEvent<SendTransactionToWalletParams>()
    val sellSwapSuccessState = SingleLiveEvent<Unit>()
    val swapTradeFailureState = SingleLiveEvent<String?>()

    val getUserAccountAddressFailedCallback = SingleLiveEvent<Unit>()
    val onFailure = SingleLiveEvent<String?>()
    val onInsufficientMoneyCallback = SingleLiveEvent<Unit>()

    var isFirstTime = true

    fun commitSwapTrade(tradeId: String, inputCurrency: String, inputAmount: String) = viewModelScope.launch(Dispatchers.Main) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CONFIRM, bundleOf())

        _showLoading.value = true
        when (val result = coinBaseRepository.commitSwapTrade(tradeId)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    commitSwapTradeFailureState.call()
                } else {
                    if (inputCurrency == CoinbaseConstants.DASH_CURRENCY) {
                        try {
                            val coin = Coin.parseCoin(inputAmount)
                            sellDashToCoinBase(coin)
                        } catch (x: Exception) {
                            Coin.ZERO
                        }
                    } else {
                        sendFundToWalletParams = SendTransactionToWalletParams(
                            amount = result.value.displayInputAmount,
                            currency = result.value.displayInputCurrency,
                            idem = UUID.randomUUID().toString(),
                            to = walletDataProvider.freshReceiveAddress().toBase58(),
                            type = CoinbaseConstants.TRANSACTION_TYPE_SEND
                        ).apply {
                            commitSwapTradeSuccessState.value = this
                            transactionMetadataProvider.markAddressAsTransferInAsync(to!!, ServiceName.Coinbase)
                        }
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                val error = result.errorBody?.string()
                if (error.isNullOrEmpty()) {
                    commitSwapTradeFailureState.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        commitSwapTradeFailureState.call()
                    } else {
                        commitSwapTradeFailureState.value = message ?: ""
                    }
                }
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
                    _showLoading.value = false
                    if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                        swapTradeFailureState.call()
                    } else {
                        result.value.apply {
                            this.assetsBaseID = swapTradeUIModel.assetsBaseID
                            this.inputCurrencyName =
                                swapTradeUIModel.inputCurrencyName
                            this.outputCurrencyName = swapTradeUIModel.outputCurrencyName
                        }
                        _swapTradeOrder.value = result.value
                    }
                }
                is ResponseResource.Failure -> {
                    _showLoading.value = false

                    val error = result.errorBody?.string() // TODO: this is a blocking call in main thread. Better to switch contexts
                    if (error.isNullOrEmpty()) {
                        swapTradeFailureState.call()
                    } else {
                        val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                        if (message.isNullOrEmpty()) {
                            swapTradeFailureState.call()
                        } else {
                            swapTradeFailureState.value = message
                        }
                    }
                }
            }
        }
    }

    fun onRefreshOrderClicked(swapTradeUIModel: SwapTradeUIModel) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_RETRY, bundleOf())
        swapTrade(swapTradeUIModel)
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, bundleOf())
    }

    private suspend fun sellDashToCoinBase(coin: Coin) {
        _showLoading.value = true

        when (val result = coinBaseRepository.createAddress()) {
            is ResponseResource.Success -> {
                if (result.value.isEmpty()) {
                    _showLoading.value = false
                    getUserAccountAddressFailedCallback.call()
                } else {
                    sendDashToCoinbase(coin, result.value)
                    sellSwapSuccessState.call()
                    _showLoading.value = false
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                getUserAccountAddressFailedCallback.call()
            }
        }
    }

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
