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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.MayaErrorResponse
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog
import org.dash.wallet.integrations.maya.utils.MayaConstants
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class MayaConvertResultViewModel @Inject constructor(
    private val coinBaseRepository: MayaWebApi,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val loadingState: LiveData<Boolean>
        get() = _loadingState

    private val _transactionState: MutableLiveData<TransactionState> = MutableLiveData()
    val transactionState: LiveData<TransactionState>
        get() = _transactionState

    val twoFaErrorState = SingleLiveEvent<Unit>()

    private var _isRetryingTransfer: Boolean = false

    fun isRetryingTransfer(isRetryingTransfer: Boolean) {
        _isRetryingTransfer = isRetryingTransfer
    }

    fun sendInitialTransactionToSMSTwoFactorAuth(
        params: SendTransactionToWalletParams?
    ) = viewModelScope.launch {
        val sendTransactionToWalletParams = params?.copy()
        sendTransactionToWalletParams?.let {
            coinBaseRepository.sendFundsToWallet(it, null)
        }
    }

    fun verifyUserAndCompleteTransaction(
        params: SendTransactionToWalletParams?,
        twoFaCode: String
    ) = viewModelScope.launch(Dispatchers.Main) {
        _loadingState.value = true

        val sendTransactionToWalletParams = if (_isRetryingTransfer) {
            params?.copy()
        } else {
            params
        }

        sendTransactionToWalletParams?.let {
            _isRetryingTransfer = false
            when (val result = coinBaseRepository.sendFundsToWallet(it, twoFaCode)) {
                is ResponseResource.Success -> {
                    _loadingState.value = false
                    if (result.value == null) {
                        _transactionState.value = TransactionState(false)
                    } else {
                        _transactionState.value = TransactionState(true)
                    }
                }

                is ResponseResource.Failure -> {
                    _loadingState.value = false
                    try {
                        val error = result.errorBody
                        if (result.errorCode == 400 || result.errorCode == 402 || result.errorCode == 429) {
                            error?.let { errorMsg ->
                                val errorContent = MayaErrorResponse.getErrorMessage(errorMsg)
                                if (errorContent?.id.equals(MayaConstants.ERROR_ID_INVALID_REQUEST, true) &&
                                    errorContent?.message?.contains(MayaConstants.ERROR_MSG_INVALID_REQUEST) == true
                                ) {
                                    twoFaErrorState.call()
                                } else {
                                    _transactionState.value = TransactionState(false, errorContent?.message)
                                }
                            }
                        } else {
                            _transactionState.value = TransactionState(false, null)
                        }
                    } catch (e: IOException) {
                        _transactionState.value = TransactionState(false, null)
                    }
                }
            }
        }
    }

    fun logRetry(type: MayaResultDialog.Type) {
        when (type) {
            MayaResultDialog.Type.DEPOSIT_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.BUY_ERROR_RETRY, mapOf())
            }
            MayaResultDialog.Type.CONVERSION_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.CONVERT_ERROR_RETRY, mapOf())
            }
            MayaResultDialog.Type.TRANSFER_DASH_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.TRANSFER_ERROR_RETRY, mapOf())
            }
            else -> {}
        }
    }

    fun logClose(type: MayaResultDialog.Type) {
        when (type) {
            MayaResultDialog.Type.DEPOSIT_SUCCESS -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.BUY_SUCCESS_CLOSE, mapOf())
            }
            MayaResultDialog.Type.DEPOSIT_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.BUY_ERROR_CLOSE, mapOf())
            }
            MayaResultDialog.Type.CONVERSION_SUCCESS -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.CONVERT_SUCCESS_CLOSE, mapOf())
            }
            MayaResultDialog.Type.CONVERSION_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.CONVERT_ERROR_CLOSE, mapOf())
            }
            MayaResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.TRANSFER_SUCCESS_CLOSE, mapOf())
            }
            MayaResultDialog.Type.TRANSFER_DASH_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.TRANSFER_ERROR_CLOSE, mapOf())
            }
            else -> {}
        }
    }
}

data class TransactionState(
    val isTransactionSuccessful: Boolean,
    val responseMessage: String? = null
)
