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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.rates.ExchangeRate
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.rates.ExchangeRatesRepository.GetExchangeRateCallback
import de.schildbach.wallet.ui.SingleLiveEvent
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat

class EnterAmountSharedViewModel(application: Application) : AndroidViewModel(application) {

    private lateinit var repo: ExchangeRatesRepository
    private var _nameLiveData = MutableLiveData<ExchangeRate>()
    val exchangeRateData: LiveData<ExchangeRate>
        get() = _nameLiveData

    fun setCurrentExchangeRate(selectedExchangeRate: ExchangeRate) {
        _nameLiveData.value = selectedExchangeRate
    }

    var exchangeRate: org.bitcoinj.utils.ExchangeRate? = null
        get() = _nameLiveData.value?.run { org.bitcoinj.utils.ExchangeRate(Coin.COIN, _nameLiveData.value!!.fiat) }
        set(value) {
            field = value
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

    init {
        val currencyCode = (application as WalletApplication).configuration.getSendPaymentExchangeCurrencyCode()
        repo = ExchangeRatesRepository.getInstance()
        val callback: GetExchangeRateCallback = object : GetExchangeRateCallback {
            override fun onExchangeRateLoaded(exchangeRate: ExchangeRate) {
                _nameLiveData.value = exchangeRate
            }
        }
        repo.getExchangeRate(currencyCode, callback)
    }

    fun hasAmount(): Boolean {
        return Coin.ZERO.isLessThan(dashAmountData.value)
    }
}
