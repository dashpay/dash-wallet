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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.ui.convert_currency.model.BaseIdForFiatData
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class CoinbaseUIState(
    val baseIdForFiatModel: BaseIdForFiatData = BaseIdForFiatData.LoadingState,
    val isSessionExpired: Boolean = false
)

@HiltViewModel
class CoinbaseViewModel @Inject constructor(
    private val config: CoinbaseConfig,
    private val walletUIConfig: WalletUIConfig,
    private val coinBaseRepository: CoinBaseRepositoryInt
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(CoinbaseViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(CoinbaseUIState())
    val uiState: StateFlow<CoinbaseUIState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            config.observe(CoinbaseConfig.LAST_REFRESH_TOKEN)
                .distinctUntilChanged()
                .filterNotNull()
                .onEach { token ->
                    _uiState.value = CoinbaseUIState(isSessionExpired = token.isEmpty())
                }
                .launchIn(viewModelScope)
        }
    }

    suspend fun loginToCoinbase(code: String): Boolean {
        when (val response = coinBaseRepository.completeCoinbaseAuthentication(code)) {
            is ResponseResource.Success -> {
                if (response.value) {
                    return true
                }
            }

            is ResponseResource.Failure -> {
                log.error("Coinbase login error ${response.errorCode}: ${response.errorBody ?: "empty"}")
            }
        }

        return false
    }
    fun getBaseIdForFiatModel() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(baseIdForFiatModel = BaseIdForFiatData.LoadingState)

        when (
            val response = coinBaseRepository.getBaseIdForUSDModel(
                walletUIConfig.getExchangeCurrencyCode()
            )
        ) {
            is ResponseResource.Success -> {
                _uiState.value = _uiState.value.copy(
                    baseIdForFiatModel = if (response.value?.data != null) {
                        BaseIdForFiatData.Success(response.value?.data!!)
                    } else {
                        BaseIdForFiatData.LoadingState
                    }
                )
            }

            is ResponseResource.Failure -> {
                runBlocking { config.set(CoinbaseConfig.UPDATE_BASE_IDS, true) }
                _uiState.value = _uiState.value.copy(baseIdForFiatModel = BaseIdForFiatData.Error)
            }
            else -> { }
        }
    }

    fun clearWasLoggedOut() {
        _uiState.value = _uiState.value.copy(isSessionExpired = false)
    }

    // TODO:
    private fun splitNameAndAccount(nameAccount: String?, type: PaymentMethodType): Pair<String, String> {
        nameAccount?.let {
            val match = when (type) {
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
            "apple_pay" -> PaymentMethodType.ApplePay
            else -> PaymentMethodType.Unknown
        }
    }
}
