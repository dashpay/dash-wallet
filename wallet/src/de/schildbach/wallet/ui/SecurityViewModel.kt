/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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