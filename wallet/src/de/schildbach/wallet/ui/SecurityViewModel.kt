/*
 * Copyright 2022 Dash Core Group.
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


package de.schildbach.wallet.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.GenericUtils
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class SecurityViewModel @Inject constructor(var exchangeRates: ExchangeRatesProvider,
                                            var configuration: Configuration)
    : ViewModel() {

    private val _selectedCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    private val _selectedExchangeRate = MutableLiveData<ExchangeRate>()
    val selectedExchangeRate: LiveData<ExchangeRate>
        get() = _selectedExchangeRate



    init {
        _selectedCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_selectedExchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun getBalanceInLocalFormat(balanceInFiat: Fiat?): String {
        return if (balanceInFiat == null){
            formatFiatBalance(Fiat.parseFiat(_selectedCurrencyCode.value, "0"))
        } else {
            formatFiatBalance(balanceInFiat)
        }
    }

    private fun formatFiatBalance(fiat: Fiat): String {
        val localCurrencySymbol = GenericUtils.currencySymbol(_selectedCurrencyCode.value)
        return if (GenericUtils.isCurrencyFirst(fiat)) {
            "$localCurrencySymbol ${fiat.toPlainString()}"
        } else {
            "${fiat.toPlainString()} $localCurrencySymbol"
        }
    }
}