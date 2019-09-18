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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat

class EnterAmountViewModel : ViewModel() {

    val dashToFiatDirectionData = MutableLiveData<Boolean>()
    val dashToFiatDirectionValue: Boolean
        get() = (dashToFiatDirectionData.value == true)

    val dashAmountData = MutableLiveData<Coin>()
    val dashAmountValue: Coin?
        get() = dashAmountData.value

    val fiatAmountData = MutableLiveData<Fiat>()
    val fiatAmountValue: Fiat?
        get() = fiatAmountData.value

    var exchangeRate: ExchangeRate? = null
        set(value) {
            field = value
            calculateDependent()
        }

    fun setDashAmount(amount: Coin) {
        dashAmountData.value = amount
    }

    fun setFiatAmount(amount: Fiat) {
        fiatAmountData.value = amount
    }

    fun calculateDependent() {
        exchangeRate!!.also {
            if (dashToFiatDirectionValue) {
                fiatAmountData.value = it.coinToFiat(dashAmountValue)
            } else {
                dashAmountData.value = it.fiatToCoin(fiatAmountValue)
            }
        }
    }
}
