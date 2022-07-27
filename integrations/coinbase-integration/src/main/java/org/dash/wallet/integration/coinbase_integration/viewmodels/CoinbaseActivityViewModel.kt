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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class CoinbaseActivityViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepositoryInt
) : AndroidViewModel(application){

    private val _uiState = MutableStateFlow<PaymentMethodsUiState>(PaymentMethodsUiState.LoadingState(true))
    val uiState: StateFlow<PaymentMethodsUiState> = _uiState


    fun getPaymentMethods() = viewModelScope.launch(Dispatchers.Main) {
        _uiState.value = PaymentMethodsUiState.LoadingState(true)

        when (val response = coinBaseRepository.getActivePaymentMethods()) {
            is ResponseResource.Success -> {
                _uiState.value = PaymentMethodsUiState.LoadingState(false)

                if (response.value.isEmpty()) {
                    _uiState.value = PaymentMethodsUiState.Error(true)
                } else {

                        val result = response.value.filter { it.isBuyingAllowed == true }
                            .map {
                                val type = paymentMethodTypeFromCoinbaseType(it.type ?: "")
                                val nameAccountPair = splitNameAndAccount(it.name, type)
                                PaymentMethod(
                                    it.id ?: "",
                                    nameAccountPair.first,
                                    nameAccountPair.second,
                                    "", // set "Checking" to get "****1234 • Checking" in subtitle
                                    paymentMethodType = type
                                )
                            }
                    _uiState.value = PaymentMethodsUiState.Success(result)
                }
            }
            is ResponseResource.Failure -> {
                _uiState.value = PaymentMethodsUiState.Error(true)

            }
        }
    }

    private fun splitNameAndAccount(nameAccount: String?, type: PaymentMethodType): Pair<String, String> {
        nameAccount?.let {
            val match = when(type) {
                PaymentMethodType.BankAccount, PaymentMethodType.Card, PaymentMethodType.PayPal -> {
                    "(\\d+)?\\s?[a-z]?\\*+".toRegex().find(nameAccount)
                }
                PaymentMethodType.Fiat -> {
                    "\\(.*\\)".toRegex().find(nameAccount)
                }
                else -> null
            }

            return match?.range?.first?.let { index ->
                val name = nameAccount.substring(0, index).trim(' ', '-', ',', ':')
                val account = nameAccount.substring(index, nameAccount.length).trim()
                return Pair(name, account)
            } ?: Pair(nameAccount, "")
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



// Represents different states for the LatestNews screen
sealed class PaymentMethodsUiState {
    data class Success(val paymentMethodsList: List<PaymentMethod>): PaymentMethodsUiState()
    data class Error(val isError:Boolean): PaymentMethodsUiState()
    data class LoadingState(val isLoading:Boolean): PaymentMethodsUiState()
}