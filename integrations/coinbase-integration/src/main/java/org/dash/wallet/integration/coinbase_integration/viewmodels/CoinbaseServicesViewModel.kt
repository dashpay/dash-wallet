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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountData
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import javax.inject.Inject

@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val exchangeRatesProvider: ExchangeRatesProvider,
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

    private val _activePaymentMethods: MutableLiveData<Event<List<PaymentMethod>>> = MutableLiveData()
    val activePaymentMethods: LiveData<Event<List<PaymentMethod>>>
        get() = _activePaymentMethods

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    val activePaymentMethodsFailureCallback = SingleLiveEvent<Unit>()
    val coinbaseLogOutCallback = SingleLiveEvent<Unit>()

    private fun getUserAccountInfo() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val response = coinBaseRepository.getUserAccount()) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (response.value == null) {
                    _userAccountError.value = true
                } else {
                    _user.value = response.value
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }

    fun disconnectCoinbaseAccount() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        coinBaseRepository.disconnectCoinbaseAccount()
        _showLoading.value = false
        coinbaseLogOutCallback.call()
    }

    init {
        getUserAccountInfo()
        exchangeRatesProvider.observeExchangeRate(config.exchangeCurrencyCode)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun getPaymentMethods() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val response = coinBaseRepository.getActivePaymentMethods()) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                if (response.value.isEmpty()) {
                    activePaymentMethodsFailureCallback.call()
                } else {
                    _activePaymentMethods.value = Event(
                        response.value.filter { it.isBuyingAllowed == true }
                            .map {
                                val type = paymentMethodTypeFromCoinbaseType(it.type ?: "")
                                val nameAccountPair = splitNameAndAccount(it.name)
                                PaymentMethod(
                                    it.id ?: "",
                                    nameAccountPair.first,
                                    nameAccountPair.second,
                                    "", // set "Checking" to get "****1234 • Checking" in subtitle
                                    paymentMethodType = type
                                )
                            })
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                activePaymentMethodsFailureCallback.call()
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
