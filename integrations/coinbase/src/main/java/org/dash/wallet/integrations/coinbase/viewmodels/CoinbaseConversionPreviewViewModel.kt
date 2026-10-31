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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.freshReceiveAddressStringOffMain
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.isInsufficientMoney
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.model.*
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.dash.wallet.common.services.ReceiveAddressUnavailableException

@HiltViewModel
class CoinbaseConversionPreviewViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    private val analyticsService: AnalyticsService,
    networkState: NetworkStateInt,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    companion object {
        private const val KEY_PAYMENT_OUTCOME_UNKNOWN = "payment_outcome_unknown"
    }

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

    /**
     * True once the sale's Dash send may have gone out with its outcome unknown. Terminal: no
     * further commit is accepted, and it is kept in [savedStateHandle] so a recreated screen (or
     * process) still shows the warning instead of a Confirm button that could pay twice.
     */
    val paymentOutcomeUnknown: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_PAYMENT_OUTCOME_UNKNOWN, false)

    var isFirstTime = true

    /** Held while one commit (and its send) is in progress (see [commitSwapTrade]). */
    private val commitInFlight = AtomicBoolean(false)

    fun commitSwapTrade(tradeId: String, inputCurrency: String, inputAmount: String): Job {
        // Claimed synchronously, before launching, so two rapid Confirms cannot both commit and
        // send. Refused for good once an earlier send may have gone out.
        if (paymentOutcomeUnknown.value || !commitInFlight.compareAndSet(false, true)) {
            return Job().apply { complete() }
        }
        return viewModelScope.launch {
            try {
                commitAndSend(tradeId, inputCurrency, inputAmount)
            } finally {
                // An unknown outcome has already set the terminal flag, which keeps refusing.
                commitInFlight.set(false)
            }
        }
    }

    private suspend fun commitAndSend(tradeId: String, inputCurrency: String, inputAmount: String) {
        // The wallet the user confirmed on, before the address and trade calls below wait (review
        // 4304575003): a DASH sale is refused, with nothing sent, if a wipe replaces it meanwhile.
        val session = sendPaymentService.currentWalletSession()
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CONFIRM, mapOf())

        _showLoading.value = true

        // Acquire the destination BEFORE committing the trade. The commit is the
        // financial side effect; reading the address after it meant an
        // unavailable address reported a SUCCEEDED conversion as a failure, with
        // no resumable state and no withdrawal. Only the non-DASH input branch
        // needs it — the DASH branch sells instead of depositing.
        val depositAddress = if (inputCurrency == Constants.DASH_CURRENCY) {
            null
        } else {
            try {
                walletDataProvider.freshReceiveAddressStringOffMain()
            } catch (ex: ReceiveAddressUnavailableException) {
                _showLoading.value = false
                commitSwapTradeFailureState.call()
                return
            }
        }

        when (val result = coinBaseRepository.commitSwapTrade(tradeId)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    commitSwapTradeFailureState.call()
                } else {
                    if (inputCurrency == Constants.DASH_CURRENCY) {
                        try {
                            val coin = Dash.parse(inputAmount)
                            sellDashToCoinBase(coin, session)
                        } catch (x: Exception) {
                            Dash.ZERO
                        }
                    } else {
                        // Non-null by construction — this is the same
                        // `inputCurrency != DASH` branch that populated it above —
                        // but that invariant is non-local, so bind it rather than
                        // force-unwrap. If the two branches ever drift apart this
                        // fails the commit instead of throwing on a null address.
                        val to = depositAddress ?: run {
                            commitSwapTradeFailureState.call()
                            return
                        }
                        sendFundToWalletParams = SendTransactionToWalletParams(
                            amount = result.value.displayInputAmount,
                            currency = result.value.displayInputCurrency,
                            idem = UUID.randomUUID().toString(),
                            to = to,
                            type = CoinbaseConstants.TRANSACTION_TYPE_SEND
                        ).apply {
                            commitSwapTradeSuccessState.value = this
                            transactionMetadataProvider.markAddressAsTransferInAsync(to, ServiceName.Coinbase)
                        }
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                val error = result.errorBody
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

    private fun swapTrade(swapTradeUIModel: SwapTradeUIModel) = viewModelScope.launch {
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

                    val error = result.errorBody
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
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_RETRY, mapOf())
        swapTrade(swapTradeUIModel)
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    private suspend fun sellDashToCoinBase(coin: Dash, session: WalletSession?) {
        _showLoading.value = true

        when (val result = coinBaseRepository.createAddress()) {
            is ResponseResource.Success -> {
                if (result.value.isEmpty()) {
                    _showLoading.value = false
                    getUserAccountAddressFailedCallback.call()
                } else {
                    val outcomeUnknown = sendDashToCoinbase(coin, result.value, session)
                    if (!outcomeUnknown) {
                        sellSwapSuccessState.call()
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

    /**
     * Returns true when the send's outcome is unknown ([paymentOutcomeUnknown] was set),
     * so the caller must not report the sale as successful either.
     */
    private suspend fun sendDashToCoinbase(coin: Dash, addressInfo: String, session: WalletSession?): Boolean {
        val address = addressInfo.trim { it <= ' ' }
        return try {
            sendPaymentService.sendCoins(address, coin, checkBalanceConditions = false, session = session)
            transactionMetadataProvider.markAddressAsTransferOutAsync(
                address,
                ServiceName.Coinbase
            )
            false
        } catch (x: PaymentSubmissionPendingException) {
            savedStateHandle[KEY_PAYMENT_OUTCOME_UNKNOWN] = true
            x.printStackTrace()
            true
        } catch (x: Exception) {
            if (x.isInsufficientMoney) {
                onInsufficientMoneyCallback.call()
                x.printStackTrace()
            } else {
                onFailure.value = x.message
                x.printStackTrace()
            }
            false
        }
    }
}
