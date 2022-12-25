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

package de.schildbach.wallet.ui.send

import android.app.Application
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import org.dash.wallet.common.data.ExchangeRate
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.ui.SingleLiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Inject

@HiltViewModel
class EnterAmountSharedViewModel @Inject constructor(
    private val configuration: Configuration,
    private val walletData: WalletDataProvider
) : ViewModel() {
    private var repo: ExchangeRatesRepository
    private val _nameLiveData = MutableLiveData<ExchangeRate>()
    val exchangeRateData: LiveData<ExchangeRate>
        get() = _nameLiveData

    fun setCurrentExchangeRate(selectedExchangeRate: ExchangeRate) {
        _nameLiveData.value = selectedExchangeRate
    }

    val exchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = _nameLiveData.value?.run { org.bitcoinj.utils.ExchangeRate(Coin.COIN, _nameLiveData.value!!.fiat) }

    private val dashToFiatDirectionData = MutableLiveData<Boolean>()
    val dashToFiatDirectionLiveData: LiveData<Boolean>
        get() = dashToFiatDirectionData

    fun setDashToFiatDirection(isDashToFiat: Boolean) {
        dashToFiatDirectionData.value = isDashToFiat
    }

    val dashAmountData = MutableLiveData<Coin>()

    val dashAmount: Coin
        get() = dashAmountData.value!!

    val directionChangeEnabledData = MutableLiveData<Boolean>()

    val buttonEnabledData = MutableLiveData<Boolean>()

    val editableData = MutableLiveData<Boolean>()

    val maxButtonVisibleData = MutableLiveData<Boolean>()

    val buttonTextData = SingleLiveEvent<Int>()

    val messageTextData = MutableLiveData<Int>()

    val messageTextStringData = MutableLiveData<CharSequence>()

    val changeDashAmountEvent = SingleLiveEvent<Coin>()

    val applyMaxAmountEvent = SingleLiveEvent<Coin>()

    val buttonClickEvent = SingleLiveEvent<Coin>()

    val maxButtonClickEvent = SingleLiveEvent<Boolean>()

    val dashPayProfileData = MutableLiveData<DashPayProfile>()
    
    val receiveAddress: Address
        get() = walletData.freshReceiveAddress()

    init {
        val currencyCode = configuration.exchangeCurrencyCode
        repo = ExchangeRatesRepository.instance
        viewModelScope.launch(Dispatchers.Main) {
            currencyCode?.let {
                val result = repo.getExchangeRate(currencyCode)
                result.let { _nameLiveData.value = it } // do nothing if null
            }
        }
    }

    fun hasAmount(): Boolean {
        return Coin.ZERO.isLessThan(dashAmountData.value)
    }
}
