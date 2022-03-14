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
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class EnterAmountViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration
) : ViewModel() {
    private val _selectedCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var selectedCurrencyCode: String
        get() = _selectedCurrencyCode.value
        set(value) {
            _selectedCurrencyCode.value = value
        }

    private val _selectedExchangeRate = MutableLiveData<ExchangeRate>()
    val selectedExchangeRate: LiveData<ExchangeRate>
        get() = _selectedExchangeRate

    val onContinueEvent = SingleLiveEvent<Pair<Coin, Fiat>>()

    internal val _dashToFiatDirection = MutableLiveData<Boolean>()
    val dashToFiatDirection: LiveData<Boolean>
        get() = _dashToFiatDirection

    private val _maxAmount = MutableLiveData(Coin.ZERO)
    val maxAmount: LiveData<Coin>
        get() = _maxAmount

    internal val _amount = MutableLiveData<Coin>()
    val amount: LiveData<Coin>
        get() = _amount

    val canContinue: LiveData<Boolean>
        get() = MediatorLiveData<Boolean>().also { liveData ->
            fun getValue(amount: Coin, maxAmount: Coin): Boolean {
                return amount > Coin.ZERO && (maxAmount == Coin.ZERO || amount <= maxAmount)
            }

            liveData.addSource(_amount) {
                liveData.value = getValue(it, _maxAmount.value ?: Coin.ZERO)
            }
            liveData.addSource(_maxAmount) {
                liveData.value = getValue(_amount.value ?: Coin.ZERO, it)
            }
            liveData.addSource(_dashToFiatDirection) {
                liveData.value = getValue(_amount.value ?: Coin.ZERO, _maxAmount.value ?: Coin.ZERO)
            }
        }

    init {
        _selectedCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_selectedExchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun setMaxAmount(coin: Coin) {
        _maxAmount.value = coin
    }
}
