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
package org.dash.wallet.integration.coinbase_integration.ui.dialogs.crypto_wallets

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CryptoWalletsDialogViewModel @Inject constructor(
    private val exchangeRatesProvider: ExchangeRatesProvider,
    val config: Configuration,
    walletUIConfig: WalletUIConfig
) : ViewModel() {

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _dataList = MutableLiveData<List<CoinBaseUserAccountDataUIModel>>()
    val dataList: LiveData<List<CoinBaseUserAccountDataUIModel>>
        get() = _dataList

    init {
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun submitList(list: List<CoinBaseUserAccountDataUIModel>) {
        _dataList.postValue(list)
    }
}
