/*
 * Copyright 2020 Dash Core Group
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

package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import javax.inject.Inject

@HiltViewModel
class NewAccountConfirmDialogViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val exchangeRatesProvider: ExchangeRatesProvider
) : ViewModel() {

    private val _exchangeRateData = MutableLiveData<ExchangeRate?>()
    val exchangeRateData: LiveData<ExchangeRate?>
        get() = _exchangeRateData

    val exchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = exchangeRateData.value?.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
        }

    init {
        val currencyCode = walletApplication.configuration.exchangeCurrencyCode
        exchangeRatesProvider.observeExchangeRate(currencyCode!!)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach(_exchangeRateData::postValue)
            .launchIn(viewModelScope)
    }
}
