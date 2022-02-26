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

package org.dash.wallet.integrations.crowdnode.ui.portal

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import javax.inject.Inject

@HiltViewModel
class PortalViewModel @Inject constructor(
    private val config: Configuration,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    private val crowdNodeApi: CrowdNodeApi
) : ViewModel() {

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _balance: MutableLiveData<Coin> = MutableLiveData()
    val balance: LiveData<Coin>
        get() = _balance

    init {
        exchangeRatesProvider.observeExchangeRate(config.exchangeCurrencyCode)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        _balance.value = Coin.valueOf((Coin.COIN.value * 1.64).toLong())
    }

    fun deposit() {
        viewModelScope.launch {
            crowdNodeApi.deposit(crowdNodeApi.accountAddress!!)
        }
    }
}