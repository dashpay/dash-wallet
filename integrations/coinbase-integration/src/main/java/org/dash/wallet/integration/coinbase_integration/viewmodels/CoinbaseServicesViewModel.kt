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
import android.app.Application
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountData
import org.dash.wallet.integration.coinbase_integration.model.CoinbasePaymentMethod
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.integration.coinbase_integration.TRANSACTION_STATUS_COMPLETED
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    val config: Configuration,
    private val walletDataProvider: WalletDataProvider
) : AndroidViewModel(application) {

    private val _user: MutableLiveData<CoinBaseUserAccountData> = MutableLiveData()
    val user: LiveData<CoinBaseUserAccountData>
        get() = _user

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _userAccountError: MutableLiveData<Boolean> = MutableLiveData()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    private val _userPaymentMethodsError: MutableLiveData<Boolean> = MutableLiveData()
    val userPaymentMethodsError: LiveData<Boolean>
        get() = _userPaymentMethodsError

    private val _userPaymentMethodsList: MutableLiveData<List<CoinbasePaymentMethod>> = MutableLiveData()
    val userPaymentMethodsList: LiveData<List<CoinbasePaymentMethod>>
        get() = _userPaymentMethodsList

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _activePaymentMethods: MutableLiveData<List<PaymentMethod>> = MutableLiveData()
    val activePaymentMethods: LiveData<List<PaymentMethod>>
        get() = _activePaymentMethods

    private val _placeBuyOrder: MutableLiveData<PlaceBuyOrderUIModel> = MutableLiveData()
    val placeBuyOrder: LiveData<PlaceBuyOrderUIModel>
        get() = _placeBuyOrder

    val activePaymentMethodsFailureCallback = SingleLiveEvent<Unit>()
    val placeBuyOrderFailedCallback = SingleLiveEvent<Unit>()
    val commitBuyOrderFailedCallback = SingleLiveEvent<Unit>()

    private val _transactionCompleted: MutableLiveData<Boolean> = MutableLiveData()
    val transactionCompleted: LiveData<Boolean>
        get() = _transactionCompleted

    private fun getUserAccountInfo() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
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
        exchangeRatesProvider.observeExchangeRate(config.exchangeCurrencyCode)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun getPaymentMethods() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when(val response = coinBaseRepository.getActivePaymentMethods()){
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (response.value.isEmpty()){
                    activePaymentMethodsFailureCallback.call()
                } else {
                    _activePaymentMethods.value = response.value.filter { it.isBuyingAllowed == true }
                            .map {
                                val type = paymentMethodTypeFromCoinbaseType(it.type ?: "")
                                val nameAccountPair = splitNameAndAccount(it.name)
                                PaymentMethod(
                                    it.id?: "",
                                    nameAccountPair.first,
                                    nameAccountPair.second,
                                    "", // set "Checking" to get "****1234 • Checking" in subtitle
                                    paymentMethodType = type
                                )
                            }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                activePaymentMethodsFailureCallback.call()
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
                    _showLoading.value = false
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

    private fun splitNameAndAccount(nameAccount: String?): Pair<String, String> {
        nameAccount?.let {
            val match = "(\\d+)?\\s?[a-z]?\\*+".toRegex().find(nameAccount)
            match?.range?.first?.let { index ->
                val name = nameAccount.substring(0, index).trim(' ', '-', ',', ':')
                val account = nameAccount.substring(index, nameAccount.length).trim()
                return Pair(name, account)
            }
        }

        return Pair("", "")
    }

    private fun paymentMethodTypeFromCoinbaseType(type: String): PaymentMethodType {
        return when (type) {
            "fiat_account" -> PaymentMethodType.Fiat
            "secure3d_card", "worldpay_card", "credit_card", "debit_card" -> PaymentMethodType.Card
            "ach_bank_account", "sepa_bank_account",
            "ideal_bank_account", "eft_bank_account", "interac" -> PaymentMethodType.BankAccount
            "bank_wire" -> PaymentMethodType.WireTransfer
            "paypal_account" -> PaymentMethodType.PayPal
            else -> PaymentMethodType.Unknown
        }
    }
}
