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
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import javax.inject.Inject

@HiltViewModel
class EnterAmountViewModel @Inject constructor(
    var walletDataProvider: WalletDataProvider
) : ViewModel() {
    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    var maxAmount: Coin = Coin.ZERO
    val onContinueEvent = SingleLiveEvent<Pair<Coin, Fiat>>()

    init {
        val defaultCurrency = walletDataProvider.defaultCurrencyCode()
        walletDataProvider.getExchangeRate(defaultCurrency).observeForever { rate ->
            rate?.let {
                _exchangeRate.postValue(ExchangeRate(Coin.COIN, rate.fiat))
            }
        }
    }
}
