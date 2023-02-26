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

package org.dash.wallet.common.ui.enter_amount

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.ExchangeRatesProvider
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnterAmountViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration
) : ViewModel() {
    private val _selectedCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var selectedCurrencyCode: String
        get() = _selectedCurrencyCode.value!!
        set(value) {
            _selectedCurrencyCode.value = value
        }

    private val _selectedExchangeRate = MutableLiveData<ExchangeRate?>()
    val selectedExchangeRate: LiveData<ExchangeRate?>
        get() = _selectedExchangeRate

    val onContinueEvent = SingleLiveEvent<Pair<Coin, Fiat>>()

    internal val _dashToFiatDirection = MutableLiveData<Boolean>()
    val dashToFiatDirection: LiveData<Boolean>
        get() = _dashToFiatDirection

    private val _minAmount = MutableLiveData(Coin.ZERO)
    val minAmount: LiveData<Coin>
        get() = _minAmount

    private val _maxAmount = MutableLiveData(Coin.ZERO)
    val maxAmount: LiveData<Coin>
        get() = _maxAmount

    internal val _amount = MutableLiveData<Coin>()
    val amount: LiveData<Coin>
        get() = _amount

    private val _callerBlocksContinue = MutableLiveData(false)
    var blockContinue: Boolean
        get() = _callerBlocksContinue.value ?: false
        set(value) { _callerBlocksContinue.value = value }

    private var _minIsIncluded = false

    val canContinue: LiveData<Boolean>
        get() = MediatorLiveData<Boolean>().apply {
            fun canContinue(): Boolean {
                val amount = _amount.value ?: Coin.ZERO
                val minAmount = _minAmount.value ?: Coin.ZERO
                val maxAmount = _maxAmount.value ?: Coin.ZERO
                val minCheck = if (_minIsIncluded) {
                    amount >= minAmount
                } else {
                    amount > minAmount
                }

                return !blockContinue && minCheck && (maxAmount == Coin.ZERO || amount <= maxAmount)
            }

            addSource(_callerBlocksContinue) { value = canContinue() }
            addSource(_amount) { value = canContinue() }
            addSource(_minAmount) { value = canContinue() }
            addSource(_maxAmount) { value = canContinue() }
            addSource(_dashToFiatDirection) { value = canContinue() }
        }

    init {
        _selectedCurrencyCode
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRates.observeExchangeRate(code)
            }
            .onEach(_selectedExchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun setMaxAmount(coin: Coin) {
        _maxAmount.value = coin
    }

    fun setMinAmount(coin: Coin, isIncludedMin: Boolean = false) {
        _minAmount.value = coin
        _minIsIncluded = isIncludedMin
    }
}
