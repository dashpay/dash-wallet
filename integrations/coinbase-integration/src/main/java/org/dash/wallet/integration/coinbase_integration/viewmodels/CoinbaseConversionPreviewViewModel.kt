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
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CoinbaseConversionPreviewViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService
) : ViewModel() {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitBuyOrderFailedCallback = SingleLiveEvent<Unit>()

    private val _transactionCompleted: MutableLiveData<TransactionState> = MutableLiveData()
    val transactionCompleted: LiveData<TransactionState>
        get() = _transactionCompleted

    var sendFundToWalletParams: SendTransactionToWalletParams? = null

    private val _swapTradeOrder: MutableLiveData<SwapTradeUIModel> = MutableLiveData()
    val swapTradeOrder: LiveData<SwapTradeUIModel>
        get() = _swapTradeOrder

    private val _commitBuyOrderSuccessCallback: MutableLiveData<SendTransactionToWalletParams> = MutableLiveData()
    val commitBuyOrderSuccessCallback: LiveData<SendTransactionToWalletParams>
        get() = _commitBuyOrderSuccessCallback

    val swapTradeFailedCallback = SingleLiveEvent<String>()

    val getUserAccountAddressFailedCallback = SingleLiveEvent<Unit>()
    val sendDashToCoinBaseFailed = SingleLiveEvent<Unit>()
    val onFailure = SingleLiveEvent<String>()
    val onInsufficientMoneyCallback = SingleLiveEvent<Unit>()

    fun commitSwapTrade(params: SwapTradeUIModel) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val result = coinBaseRepository.commitSwapTrade(params.swapTradeId)) {
            is ResponseResource.Success -> {
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    _showLoading.value = false
                    commitBuyOrderFailedCallback.call()
                } else {
                    if (params.inputCurrencyName.lowercase() == "dash") {
                        try {
                            val coin = Coin.parseCoin(params.inputAmount.toString())
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
                            type = "send"
                        ).apply {
                            _commitBuyOrderSuccessCallback.value = this
                        }
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                commitBuyOrderFailedCallback.call()
            }
        }
    }

    fun sendDash(api2FATokenVersion: String) = viewModelScope.launch(Dispatchers.Main) {
        commitBuyOrderSuccessCallback.value?.let {
            sendDashToWallet(it, api2FATokenVersion)
        }
    }

    fun sendDashToWallet(params: SendTransactionToWalletParams, api2FATokenVersion: String) = viewModelScope.launch(Dispatchers.Main) {
        if (_showLoading.value == false)
            _showLoading.value = true
        when (val result = coinBaseRepository.sendFundsToWallet(params, api2FATokenVersion)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == null) {
                    _transactionCompleted.value = TransactionState(false, null)
                } else {
                    _transactionCompleted.value = TransactionState(true, null)
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                val error = result.errorBody?.string()
                if (result.errorCode == 400) {
                    error?.let {
                        val message = CoinbaseErrorResponse.getErrorMessage(it)
                        _transactionCompleted.value = TransactionState(false, message)
                    }
                } else {
                    _transactionCompleted.value = TransactionState(false, null)
                }
            }
        }
    }


    fun swapTrade(swapTradeUIModel: SwapTradeUIModel) = viewModelScope.launch(Dispatchers.Main) {
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
                        val message = CoinbaseErrorResponse.getErrorMessage(error)
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

    fun sellDashToCoinBase(coin: Coin) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true


        when (val result = coinBaseRepository.createAddress()) {
            is ResponseResource.Success -> {
                if (result.value?.isEmpty() == true) {
                    _showLoading.value = false
                    getUserAccountAddressFailedCallback.call()
                } else {
                    result.value?.let {
                        sendDashToCoinbase(coin, result.value)
                        _transactionCompleted.value = TransactionState(true, null)
                        _showLoading.value = false
                    }
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
        val address = walletDataProvider.createSentDashAddress(addressInfo)
        try {
            val transaction = sendPaymentService.sendCoins(address, coin)
            return transaction.isPending
        } catch (x: InsufficientMoneyException) {
            onInsufficientMoneyCallback.call()
            x.printStackTrace()
            return false
        } catch (ex: Exception) {
            onFailure.value = ex.message
            ex.printStackTrace()
            return false
        }
    }
}
