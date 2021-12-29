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

    var maxAmount: Coin = Coin.ZERO
    val onContinueEvent = SingleLiveEvent<Pair<Coin, Fiat>>()

    init {
        _selectedCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_selectedExchangeRate::postValue)
            .launchIn(viewModelScope)
    }
}
