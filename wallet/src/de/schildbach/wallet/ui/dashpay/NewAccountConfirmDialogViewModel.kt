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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.rates.ExchangeRate
import de.schildbach.wallet.rates.ExchangeRatesRepository
import org.bitcoinj.core.Coin

class NewAccountConfirmDialogViewModel(application: Application) : AndroidViewModel(application) {

    val walletApplication = application as WalletApplication
    val exchangeRateData: LiveData<ExchangeRate>

    val exchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = exchangeRateData.value?.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
        }

    init {
        val currencyCode = walletApplication.configuration.exchangeCurrencyCode
        exchangeRateData = ExchangeRatesRepository.instance.getRate(currencyCode)
    }
}
