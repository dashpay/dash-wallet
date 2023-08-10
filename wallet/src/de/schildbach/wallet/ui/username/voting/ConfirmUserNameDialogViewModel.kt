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

package de.schildbach.wallet.ui.username.voting

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.GenericUtils
import javax.inject.Inject


data class ConfirmUserNameUIState(
    val amountStr: String = "",
    val fiatSymbol: String = "",
    val fiatAmountStr: String = "",
    val usernameSubmittedSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false
)

@HiltViewModel
class ConfirmUserNameDialogViewModel @Inject constructor(
    var configuration: Configuration,
    private val exchangeRatesProvider: ExchangeRatesProvider

) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfirmUserNameUIState())
    val uiState: StateFlow<ConfirmUserNameUIState> = _uiState.asStateFlow()

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val amount by lazy {
        Coin.valueOf(Constants.DASH_PAY_FEE.value)
    }

    private val currencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)

    init {

        currencyCode.filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
    }


    fun updateFees(exchangeRateData: ExchangeRate) {
        val amountStr = MonetaryFormat.BTC.noCode().format(amount).toString()

        val exchangeRate = exchangeRateData.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
        }
        val fiatAmount = exchangeRate.coinToFiat(amount)

        val fiatAmountStr = if (fiatAmount != null) Constants.LOCAL_FORMAT.format(fiatAmount).toString() else ""
        val fiatSymbol = if (fiatAmount != null) GenericUtils.currencySymbol(fiatAmount.currencyCode) else ""
        _uiState.update {
            it.copy(
                amountStr = amountStr,
                fiatAmountStr = fiatAmountStr,
                fiatSymbol = fiatSymbol
            )
        }
    }
}
