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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorResponse
import org.dash.wallet.integrations.coinbase.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.ui.dialogs.CoinBaseResultDialog
import retrofit2.HttpException
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EnterTwoFaCodeViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    private lateinit var transactionParams: SendTransactionToWalletParams
    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val loadingState: LiveData<Boolean>
        get() = _loadingState

    private val _transactionState: MutableLiveData<TransactionState> = MutableLiveData()
    val transactionState: LiveData<TransactionState>
        get() = _transactionState

    val twoFaErrorState = SingleLiveEvent<Unit>()

    fun sendInitialTransactionToSMSTwoFactorAuth(params: SendTransactionToWalletParams) = viewModelScope.launch {
        transactionParams = params.copy(idem = UUID.randomUUID().toString())

        try {
            coinBaseRepository.sendFundsToWallet(transactionParams, null)
        } catch (ex: HttpException) {
            // Meant to fail with 2fa required error

            // TODO: does every account has 2fa?
            //  iOS does a regular request first and only requires 2fa input in case of failure
        }
    }

    fun verifyUserAndCompleteTransaction(twoFaCode: String) = viewModelScope.launch {
        _loadingState.value = true

        try {
            // 2fa request must have same parameters, including idem
            val result = coinBaseRepository.sendFundsToWallet(transactionParams, twoFaCode)
            _loadingState.value = false
            _transactionState.value = TransactionState(result != null)
        } catch (ex: Exception) {
            _loadingState.value = false
            var errorMessage = ex.message ?: ex.toString()

            if (ex is HttpException) {
                if (ex.code() == 400 || ex.code() == 402 || ex.code() == 429) {
                    val error = ex.response()?.errorBody()?.string()
                    error?.let { errorMsg ->
                        val errorContent = CoinbaseErrorResponse.getErrorMessage(errorMsg)

                        if (errorContent?.isInvalidRequest == true) {
                            twoFaErrorState.call()
                            return@launch
                        } else {
                            errorContent?.message?.let { errorMessage = it }
                        }
                    }
                }
            }

            _transactionState.value = TransactionState(false, errorMessage)
        }
    }

    fun logRetry(type: CoinBaseResultDialog.Type) {
        when (type) {
            CoinBaseResultDialog.Type.DEPOSIT_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_ERROR_RETRY, mapOf())
            }
            CoinBaseResultDialog.Type.CONVERSION_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_ERROR_RETRY, mapOf())
            }
            CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_ERROR_RETRY, mapOf())
            }
            else -> {}
        }
    }

    fun logClose(type: CoinBaseResultDialog.Type) {
        when (type) {
            CoinBaseResultDialog.Type.DEPOSIT_SUCCESS -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_SUCCESS_CLOSE, mapOf())
            }
            CoinBaseResultDialog.Type.DEPOSIT_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_ERROR_CLOSE, mapOf())
            }
            CoinBaseResultDialog.Type.CONVERSION_SUCCESS -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_SUCCESS_CLOSE, mapOf())
            }
            CoinBaseResultDialog.Type.CONVERSION_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_ERROR_CLOSE, mapOf())
            }
            CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_SUCCESS_CLOSE, mapOf())
            }
            CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_ERROR_CLOSE, mapOf())
            }
            else -> {}
        }
    }
}

data class TransactionState(
    val isTransactionSuccessful: Boolean,
    val responseMessage: String? = null
)
