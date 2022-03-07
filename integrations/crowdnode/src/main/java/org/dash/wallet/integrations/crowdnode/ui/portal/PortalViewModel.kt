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

import androidx.annotation.MainThread
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.utils.ModuleConfiguration
import javax.inject.Inject

@HiltViewModel
class PortalViewModel @Inject constructor(
    private val globalConfig: Configuration,
    private val config: ModuleConfiguration,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    private val crowdNodeApi: CrowdNodeApi,
    private val walletDataProvider: WalletDataProvider
) : ViewModel() {

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _balance: MutableLiveData<Coin> = MutableLiveData()
    val balance: LiveData<Coin>
        get() = _balance

    private val _balanceLoading: MutableLiveData<Boolean> = MutableLiveData()
    val balanceLoading: LiveData<Boolean>
        get() = _balanceLoading

    val dashFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    val networkParameters: NetworkParameters
        get() = walletDataProvider.networkParameters

    val account: Address
        get() = crowdNodeApi.accountAddress!!

    init {
        exchangeRatesProvider.observeExchangeRate(globalConfig.exchangeCurrencyCode)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            _balance.value = Coin.valueOf(config.lastBalance.first())
            updateBalance()
        }
    }

    // TODO: this is for QA, needs to be changed
    fun deposit() {
        viewModelScope.launch {
            val amount = Coin.valueOf((Coin.COIN.value * 0.024837).toLong())
            crowdNodeApi.deposit(crowdNodeApi.accountAddress!!, amount)
            delay(2000)
            updateBalance()
        }
    }

    private fun updateBalance() {
        viewModelScope.launch {
            withProgress {
                val balance = crowdNodeApi.loadBalance()
                _balance.value = balance
                config.setLastBalance(balance.value)
            }
        }
    }

    @MainThread
    private suspend fun withProgress(action: suspend () -> Unit) {
        if (_balanceLoading.value == true) return

        _balanceLoading.value = true
        action.invoke()
        _balanceLoading.value = false
    }
}