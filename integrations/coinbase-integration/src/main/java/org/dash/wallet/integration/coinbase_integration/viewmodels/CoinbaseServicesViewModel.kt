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

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.integration.coinbase_integration.TRANSACTION_STATUS_COMPLETED
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val config: Configuration
) : ViewModel() {

    private val _user: MutableLiveData<CoinBaseUserAccountData> = MutableLiveData()
    val user: LiveData<CoinBaseUserAccountData>
        get() = _user

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _userAccountError: MutableLiveData<Boolean> = MutableLiveData()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    private val _activePaymentMethods: MutableLiveData<List<PaymentMethodUIModel>> = MutableLiveData()
    val activePaymentMethods: LiveData<List<PaymentMethodUIModel>>
        get() = _activePaymentMethods

    private val _placeBuyOrder: MutableLiveData<PlaceBuyOrderUIModel> = MutableLiveData()
    val placeBuyOrder: LiveData<PlaceBuyOrderUIModel>
        get() = _placeBuyOrder

    val placeBuyOrderFailedCallback = SingleLiveEvent<Unit>()
    val commitBuyOrderFailedCallback = SingleLiveEvent<Unit>()
    val transactionCompletedCallback = SingleLiveEvent<Unit>()
    val transactionInCompletedCallback = SingleLiveEvent<Unit>()

    private fun getUserAccountInfo() = viewModelScope.launch {

        when (val response = coinBaseRepository.getUserAccount()) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                val userAccountData = response.value.body()?.data?.firstOrNull {
                    it.balance?.currency?.equals("DASH") ?: false
                }

                if (userAccountData == null) {
                    _userAccountError.value = true
                } else {
                    _user.value = userAccountData
                    coinBaseRepository.saveLastCoinbaseDashAccountBalance(userAccountData.balance?.amount)
                    coinBaseRepository.saveUserAccountId(userAccountData.id)
                }
            }
            is ResponseResource.Loading -> {
                _showLoading.value = true
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }

    fun disconnectCoinbaseAccount() {
        viewModelScope.launch {
            coinBaseRepository.disconnectCoinbaseAccount()
        }
    }

    init {
        getUserAccountInfo()
    }

    fun getPaymentMethods() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when(val response = coinBaseRepository.getActivePaymentMethods()){
            is ResponseResource.Success -> {
                _showLoading.value = false
                _activePaymentMethods.value = response.value
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }

    fun placeBuyOrder(params: PlaceBuyOrderParams) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when(val result = coinBaseRepository.placeBuyOrder(params)){
            is ResponseResource.Success -> {
                if (result.value == BuyOrderResponse.EMPTY_PLACE_BUY) {
                    _showLoading.value = false
                    placeBuyOrderFailedCallback.call()
                }
                else {
                    _placeBuyOrder.value = result.value
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                placeBuyOrderFailedCallback.call()
            }
        }
    }

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
                        to = result.value.dashAddress,
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
                        transactionInCompletedCallback.call()
                    }
                    result.value.sendTransactionStatus == TRANSACTION_STATUS_COMPLETED -> {
                        transactionCompletedCallback.call()
                    }
                    else -> {
                        transactionInCompletedCallback.call()
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                transactionInCompletedCallback.call()
            }
        }
    }
}
