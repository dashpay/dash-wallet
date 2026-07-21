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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.SwapOrder
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.filters.LockedTransaction
import org.dash.wallet.integrations.maya.api.DispatchingSwapProvider
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.data.SwapOrderDao
import org.dash.wallet.integrations.maya.model.MayaErrorResponse
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeResponse
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.maya.utils.MayaConstants
import org.dash.wallet.integrations.maya.utils.SwapBackend
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class MayaConversionPreviewViewModel @Inject constructor(
    private val swapProvider: SwapProvider,
    private val dispatchingSwapProvider: DispatchingSwapProvider,
    private val walletDataProvider: WalletDataProvider,
    private val analyticsService: AnalyticsService,
    networkState: NetworkStateInt,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val swapOrderDao: SwapOrderDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(MayaConversionPreviewViewModel::class.java)
        private const val IS_LOCK_TIMEOUT_MS = 10_000L
        private const val KEY_ORDER = "swap_trade_order"
        private const val KEY_QUOTE_CREATED_AT = "quote_created_at"
    }

    /**
     * The quote/order currently shown. Backed by [savedStateHandle] so a refreshed quote (which
     * replaced the original nav argument) survives the OS killing the process; the fragment
     * restores it via [savedSwapTradeUIModel] before falling back to the nav argument.
     */
    var swapTradeUIModel: SwapTradeUIModel
        get() = requireNotNull(savedStateHandle.get<SwapTradeUIModel>(KEY_ORDER)) {
            "swapTradeUIModel accessed before it was set"
        }
        set(value) {
            savedStateHandle[KEY_ORDER] = value
        }

    /** Restore-time accessor: the persisted order, or null on a fresh first launch. */
    val savedSwapTradeUIModel: SwapTradeUIModel?
        get() = savedStateHandle[KEY_ORDER]

    /**
     * Wall-clock time (ms) the current quote was fetched; null until the countdown first starts.
     * Persisted so that after process death the screen resumes the remaining validity window —
     * or goes straight to the expired/Refresh state instead of restarting a full countdown.
     */
    var quoteCreatedAt: Long?
        get() = savedStateHandle[KEY_QUOTE_CREATED_AT]
        set(value) {
            savedStateHandle[KEY_QUOTE_CREATED_AT] = value
        }

    private val _showLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showLoading: StateFlow<Boolean>
        get() = _showLoading.asStateFlow()

    val commitSwapTradeFailureState = SingleLiveEvent<String>()

    private val _swapTradeOrder: MutableLiveData<SwapTradeUIModel> = MutableLiveData()
    val swapTradeOrder: LiveData<SwapTradeUIModel>
        get() = _swapTradeOrder

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    val commitSwapTradeSuccessState = SingleLiveEvent<SendTransactionToWalletParams>()
    val sellSwapSuccessState = SingleLiveEvent<Unit>()
    val swapTradeFailureState = SingleLiveEvent<String?>()

    val onInsufficientMoneyCallback = SingleLiveEvent<Unit>()

    fun commitSwapTrade(tradeId: String) = viewModelScope.launch {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CONFIRM, mapOf())
        val inputCurrency = swapTradeUIModel.amount.dashCode
        val inputAmount = swapTradeUIModel.amount.dash

        // TODO: this is the action to do the swap
        _showLoading.value = true
        when (val result = swapProvider.commitSwapTransaction(tradeId, swapTradeUIModel)) {
            is ResponseResource.Success -> {
                // Wait for the swap transaction to be IS-locked or confirmed on the network.
                // This verifies that the transaction was successfully broadcast and seen by peers.
                // Dash IS locks typically arrive within 1-2 seconds; we allow up to 10 seconds
                // before proceeding anyway (the tx was sent; lock may arrive later).
                val txId = result.value.txid
                if (txId != Sha256Hash.ZERO_HASH) {
                    val locked = withTimeoutOrNull(IS_LOCK_TIMEOUT_MS) {
                        walletDataProvider.observeTransactions(true, LockedTransaction(txId)).first()
                    }
                    if (locked != null) {
                        log.info("maya swap tx {} IS-locked or confirmed", txId)
                    } else {
                        log.warn("maya swap tx {} not IS-locked within {}ms timeout", txId, IS_LOCK_TIMEOUT_MS)
                    }
                }

                _showLoading.value = false
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    commitSwapTradeFailureState.call()
                } else {
                    commitSwapTradeSuccessState.value = SendTransactionToWalletParams(
                        swapTradeUIModel.amount,
                        swapTradeUIModel.feeAmount,
                        swapTradeUIModel.destinationAddress,
                        MayaConstants.TRANSACTION_TYPE_SEND,
                        txid = txId.takeIf { it != Sha256Hash.ZERO_HASH }?.toString(),
                        depositAddress = swapTradeUIModel.vaultAddress
                    )
                    val service = when (dispatchingSwapProvider.currentBackend()) {
                        SwapBackend.SWAPKIT -> ServiceName.Swapkit
                        SwapBackend.MAYA -> ServiceName.Maya
                    }
                    if (txId != Sha256Hash.ZERO_HASH) {
                        swapOrderDao.insertOrder(
                            SwapOrder(
                                txId = txId,
                                service = service,
                                provider = swapTradeUIModel.routeName?.takeIf { it.isNotEmpty() },
                                fromAsset = swapTradeUIModel.inputCurrency,
                                toAsset = swapTradeUIModel.outputCurrency,
                                toAddress = swapTradeUIModel.destinationAddress,
                                depositAddress = swapTradeUIModel.vaultAddress.takeIf { it.isNotEmpty() },
                                expectedToAmount = swapTradeUIModel.expectedOutputAmount
                                    .takeIf { it.signum() > 0 }?.toPlainString(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        transactionMetadataProvider.setTransactionService(txId, service)
                    }
                    // TODO add more information about the transaction to metadata.  it is a trade
                    transactionMetadataProvider.markAddressAsync(
                        swapTradeUIModel.vaultAddress,
                        false,
                        TaxCategory.Expense, // TODO: this should be a Trade
                        service
                    )
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                if (result.throwable is InsufficientMoneyException) {
                    onInsufficientMoneyCallback.call()
                    return@launch
                }
                val error = result.errorBody
                if (error.isNullOrEmpty()) {
                    commitSwapTradeFailureState.call()
                } else {
                    val message = MayaErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        commitSwapTradeFailureState.value = result.throwable.localizedMessage ?: ""
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
        when (val result = swapProvider.getSwapInfo(tradesRequest)) {
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
                    // Stamp the fetch time here, not in the fragment observer: _swapTradeOrder
                    // is a plain (sticky) LiveData, so the observer re-fires after a
                    // configuration change and must not treat the re-delivery as a fresh quote.
                    quoteCreatedAt = System.currentTimeMillis()
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
