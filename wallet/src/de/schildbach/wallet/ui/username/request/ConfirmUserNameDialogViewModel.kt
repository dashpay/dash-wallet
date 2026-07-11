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

package de.schildbach.wallet.ui.username.request

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.shieldedIdentityFundingRequirement
import de.schildbach.wallet.ui.username.UsernameType
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConfirmUserNameDialogViewModel @Inject constructor(
    var configuration: Configuration,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    private val walletUIConfig: WalletUIConfig
) : ViewModel() {

    var usernameType: UsernameType = UsernameType.Primary
    var isContestableUsername: Boolean = false
    var hasIdentity: Boolean = false

    /**
     * The balance paying for the creation. The dialog always shows the
     * amount actually withdrawn from the user's wallet: on the shielded
     * path that is the whole Type-20 exit denomination leaving the pool
     * (0.1 non-contested / 0.3 contested), not the underlying fee.
     */
    var paymentSource: UsernamePaymentSource = UsernamePaymentSource.DASH_BALANCE
    private val _uiState = MutableStateFlow(ConfirmUserNameUIState())
    val uiState: StateFlow<ConfirmUserNameUIState> = _uiState.asStateFlow()
    private val amount: Coin
        get() = when {
            usernameType == UsernameType.Secondary -> Coin.ZERO
            paymentSource == UsernamePaymentSource.SHIELDED_BALANCE -> {
                val fee = if (isContestableUsername) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
                shieldedIdentityFundingRequirement(Dash(fee.value))?.let { Coin.valueOf(it.duffs) } ?: fee
            }
            isContestableUsername && !hasIdentity -> Constants.DASH_PAY_FEE_CONTESTED
            isContestableUsername && hasIdentity -> Constants.DASH_PAY_FEE_CONTESTED_NAME
            else -> Constants.DASH_PAY_FEE
        }
    init {

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach {
                updateFees(it)
            }
            .launchIn(viewModelScope)
    }


    private fun updateFees(exchangeRateData: ExchangeRate) {
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
