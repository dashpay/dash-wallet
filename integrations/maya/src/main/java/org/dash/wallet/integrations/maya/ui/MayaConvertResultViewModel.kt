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
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog
import org.dash.wallet.integrations.maya.utils.MayaConstants
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class MayaConvertResultViewModel @Inject constructor(
    private val mayaWebApi: MayaWebApi,
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

    fun logRetry(type: MayaResultType) {
        when (type) {
            MayaResultType.DEPOSIT_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.BUY_ERROR_RETRY, mapOf())
            }
            MayaResultType.CONVERSION_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.CONVERT_ERROR_RETRY, mapOf())
            }
            MayaResultType.TRANSFER_DASH_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.TRANSFER_ERROR_RETRY, mapOf())
            }
            else -> {}
        }
    }

    fun logClose(type: MayaResultType) {
        when (type) {
            MayaResultType.DEPOSIT_SUCCESS -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.BUY_SUCCESS_CLOSE, mapOf())
            }
            MayaResultType.DEPOSIT_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.BUY_ERROR_CLOSE, mapOf())
            }
            MayaResultType.CONVERSION_SUCCESS -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.CONVERT_SUCCESS_CLOSE, mapOf())
            }
            MayaResultType.CONVERSION_ERROR -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.CONVERT_ERROR_CLOSE, mapOf())
            }
            MayaResultType.TRANSFER_DASH_SUCCESS -> {
                // analyticsService.logEvent(AnalyticsConstants.Maya.TRANSFER_SUCCESS_CLOSE, mapOf())
            }
            MayaResultType.TRANSFER_DASH_ERROR -> {
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
