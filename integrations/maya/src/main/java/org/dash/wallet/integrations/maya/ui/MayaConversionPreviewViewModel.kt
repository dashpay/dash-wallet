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
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.api.MayaBlockchainApi
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.MayaErrorResponse
import org.dash.wallet.integrations.maya.model.SwapTradeResponse
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.maya.utils.MayaConstants
import javax.inject.Inject

@HiltViewModel
class MayaConversionPreviewViewModel @Inject constructor(
    private val mayaWebApi: MayaWebApi,
    private val mayaBlockchainApi: MayaBlockchainApi,
    private val walletDataProvider: WalletDataProvider,
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
        _showLoading.value = true
        when (val result = mayaBlockchainApi.commitSwapTransaction(tradeId, swapTradeUIModel)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    commitSwapTradeFailureState.call()
                } else {
                    commitSwapTradeSuccessState.value = SendTransactionToWalletParams(
                        swapTradeUIModel.amount,
                        swapTradeUIModel.feeAmount,
                        swapTradeUIModel.destinationAddress,
                        MayaConstants.TRANSACTION_TYPE_SEND
                    )
                    // TODO add more information about the transaction to metadata.  it is a trade
                    transactionMetadataProvider.markAddressAsync(
                        swapTradeUIModel.vaultAddress,
                        false,
                        TaxCategory.Expense, // TODO: this should be a Trade
                        ServiceName.Maya
                    )
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                val error = result.errorBody
                if (error.isNullOrEmpty()) {
                    commitSwapTradeFailureState.call()
                } else {
                    val message = MayaErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        commitSwapTradeFailureState.call()
                    } else {
                        commitSwapTradeFailureState.value = message ?: ""
                    }
                }
            }
        }
    }

    fun swapTrade(swapTradeUIModel: SwapTradeUIModel) = viewModelScope.launch {
        _showLoading.value = true
        val tradesRequest = SwapQuoteRequest(
            swapTradeUIModel.amount,
            source_maya_asset = "DASH.DASH",
            target_maya_asset = swapTradeUIModel.outputAsset,
            fiatCurrency = swapTradeUIModel.amount.fiatCode,
            targetAddress = swapTradeUIModel.destinationAddress,
            maximum = swapTradeUIModel.maximum
        )
        when (val result = mayaWebApi.swapTradeInfo(tradesRequest)) {
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
