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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.integration.coinbase_integration.TRANSACTION_STATUS_COMPLETED
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CoinbaseBuyDashOrderReviewViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepository,
    private val walletDataProvider: WalletDataProvider,
    val config: Configuration
) : AndroidViewModel(application) {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    val commitBuyOrderFailedCallback = SingleLiveEvent<Unit>()

    private val _transactionCompleted: MutableLiveData<Boolean> = MutableLiveData()
    val transactionCompleted: LiveData<Boolean>
        get() = _transactionCompleted


    fun commitBuyOrder(params: String) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when(val result = coinBaseRepository.commitBuyOrder(params)){
            is ResponseResource.Success -> {
                if (result.value == BuyOrderResponse.EMPTY_COMMIT_BUY){
                    _showLoading.value = false
                    commitBuyOrderFailedCallback.call()
                } else {
                    val sendFundToWalletParams = SendTransactionToWalletParams(
                        amount = result.value.dashAmount,
                        currency = result.value.dashCurrency,
                        idem = UUID.randomUUID().toString(),
                        to = walletDataProvider.freshReceiveAddress().toBase58(),
                        type = result.value.transactionType
                    )
                    sendDashToWallet(sendFundToWalletParams)
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                commitBuyOrderFailedCallback.call()
            }
        }
    }

    fun sendDashToWallet(params: SendTransactionToWalletParams) = viewModelScope.launch(Dispatchers.Main){
        when(val result = coinBaseRepository.sendFundsToWallet(params)){
            is ResponseResource.Success -> {
                _showLoading.value = false
                when {
                    result.value == SendTransactionToWalletResponse.EMPTY -> {
                        _transactionCompleted.value = false
                    }
                    result.value.sendTransactionStatus == TRANSACTION_STATUS_COMPLETED -> {
                        _transactionCompleted.value = false
                    }
                    else -> {
                        _transactionCompleted.value = true
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                _transactionCompleted.value = false
            }
        }
    }

}