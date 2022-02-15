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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import java.math.RoundingMode
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class ConvertViewViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration
) : ViewModel() {

    private val _dashToCrypto = MutableLiveData<Boolean>()
    val dashToCrypto: LiveData<Boolean>
        get() = this._dashToCrypto

    var enteredConvertAmount = "0"
    var maxAmount: String = "0"
    val onContinueEvent = SingleLiveEvent<Pair<Boolean, Fiat>>()

    private val _selectedCryptoCurrencyAccount = MutableLiveData<CoinBaseUserAccountDataUIModel?>()
    val selectedCryptoCurrencyAccount: LiveData<CoinBaseUserAccountDataUIModel?>
        get() = this._selectedCryptoCurrencyAccount

    private val _selectedLocalCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var selectedLocalCurrencyCode: String
        get() = _selectedLocalCurrencyCode.value
        set(value) {
            _selectedLocalCurrencyCode.value = value
        }

    private val _selectedPickerCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var selectedPickerCurrencyCode: String
        get() = _selectedPickerCurrencyCode.value
        set(value) {
            _selectedPickerCurrencyCode.value = value
        }

    private val _enteredConvertDashAmount = MutableLiveData<Coin>()
    val enteredConvertDashAmount: LiveData<Coin>
        get() = _enteredConvertDashAmount

    private val _selectedLocalExchangeRate = MutableLiveData<ExchangeRate>()
    val selectedLocalExchangeRate: LiveData<ExchangeRate>
        get() = _selectedLocalExchangeRate

    init {
        _selectedLocalCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_selectedLocalExchangeRate::postValue)
            .launchIn(viewModelScope)
    }


    fun setSelectedCryptoCurrency(account: CoinBaseUserAccountDataUIModel) {
        maxAmount = account.coinBaseUserAccountData.balance?.amount ?: "0"
        this._selectedLocalExchangeRate.value = selectedLocalExchangeRate.value?.currencyCode?.let {
            val cleanedValue =
                1.toBigDecimal() /
                    account.currencyToDashExchangeRate.toBigDecimal()
            val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)
            ExchangeRate(
                it,
                bd.toString()
            )
        }

        this._selectedCryptoCurrencyAccount.value = account
    }

    fun setEnteredConvertDashAmount(value: Coin) {
        _enteredConvertDashAmount.value = value
    }

    fun setOnSwapDashFromToCryptoClicked(dashToCrypto: Boolean) {
        _dashToCrypto.value = dashToCrypto
    }

    fun clear() { _selectedCryptoCurrencyAccount.value = null }
}
