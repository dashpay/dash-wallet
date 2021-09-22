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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import androidx.lifecycle.LiveData

class EnterAmountViewModel(application: Application) : AndroidViewModel(application) {

    private val dashToFiatDirectionData = MutableLiveData<Boolean>()
    val dashToFiatDirectionLiveData: LiveData<Boolean>
        get() = dashToFiatDirectionData
    fun setDashToFiatDirection(isDashToFiat: Boolean) {
        dashToFiatDirectionData.value = isDashToFiat
    }

    val dashToFiatDirectionValue: Boolean
        get() = (dashToFiatDirectionData.value == true)

    private val dashAmountData = MutableLiveData<Coin>()
    val dashAmountLiveData: LiveData<Coin>
        get() = dashAmountData
    fun setDashAmount(coin: Coin) {
        dashAmountData.value = coin
    }

    private val fiatAmountData = MutableLiveData<Fiat>()
    val fiatAmountLiveData: LiveData<Fiat>
        get() = fiatAmountData
    fun setFiatAmount(fiat: Fiat) {
        fiatAmountData.value = fiat
    }

    fun calculateDependent(exchangeRate: ExchangeRate?) {
        exchangeRate?.run {
            if (dashToFiatDirectionValue) {
                fiatAmountData.value = coinToFiat(dashAmountData.value)
            } else {
                dashAmountData.value = fiatToCoin(fiatAmountData.value)
            }
        }
    }
}