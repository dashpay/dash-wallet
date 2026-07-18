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
import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.GenericUtils
import javax.inject.Inject
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat


data class ConfirmUserNameUIState(
    val amountStr: String = "",
    val fiatSymbol: String = "",
    val fiatAmountStr: String = "",
    /** The shown amount leaves the SHIELDED pool — render the source label. */
    val fromShieldedBalance: Boolean = false,
    val usernameSubmittedSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false
)

/** What the username confirm sheet must show: the amount actually spent and where it comes from. */
internal data class UsernameConfirmCost(val amount: Coin, val fromShieldedBalance: Boolean)

/**
 * The cost the confirm sheet's tap actually incurs, pure and host-testable.
 *
 * The identity-funding cost belongs to the IDENTITY (and its primary,
 * contested name), never to an additional name:
 *
 * - Secondary ("instant") confirm — ALWAYS free. The extra name adds no
 *   incremental cost: on an existing identity it is registered from the
 *   identity's credits, and in the dual flow (primary + instant submitted
 *   together at this confirm) the identity funding is the SAME amount with
 *   or without the instant name — it is disclosed on the primary confirm.
 *   Showing a price here would wrongly imply the instant name costs
 *   something.
 * - Primary shielded-funded creation: the whole Type-20 exit denomination
 *   leaves the shielded pool (0.1 non-contested / 0.3 contested) — shown
 *   here, the conceptual "paid" step.
 * - Primary L1 paths keep the fee schedule (0.25 contested / 0.03
 *   otherwise; contested-name-only registration from identity credits
 *   keeps its own fee).
 */
internal fun resolveUsernameConfirmCost(
    usernameType: UsernameType,
    isContestable: Boolean,
    hasIdentity: Boolean,
    paymentSource: UsernamePaymentSource
): UsernameConfirmCost = when {
    // The instant/secondary name is always free — the identity funding is
    // disclosed on the primary confirm and is unchanged by adding it.
    usernameType == UsernameType.Secondary ->
        UsernameConfirmCost(Coin.ZERO, fromShieldedBalance = false)
    paymentSource == UsernamePaymentSource.SHIELDED_BALANCE -> {
        val fee = if (isContestable) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
        val denomination = shieldedIdentityFundingRequirement(Dash(fee.value))
            ?.let { Coin.valueOf(it.duffs) }
            ?: fee
        UsernameConfirmCost(denomination, fromShieldedBalance = true)
    }
    isContestable && hasIdentity ->
        UsernameConfirmCost(Constants.DASH_PAY_FEE_CONTESTED_NAME, fromShieldedBalance = false)
    isContestable ->
        UsernameConfirmCost(Constants.DASH_PAY_FEE_CONTESTED, fromShieldedBalance = false)
    else ->
        UsernameConfirmCost(Constants.DASH_PAY_FEE, fromShieldedBalance = false)
}

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
    private val cost: UsernameConfirmCost
        get() = resolveUsernameConfirmCost(usernameType, isContestableUsername, hasIdentity, paymentSource)
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
        val cost = this.cost
        val amountStr = MonetaryFormat.BTC.noCode().format(cost.amount.toNeutralCoin()).toString()

        val exchangeRate = exchangeRateData.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat.toDashjFiat())
        }
        val fiatAmount = exchangeRate.coinToFiat(cost.amount)

        val fiatAmountStr = if (fiatAmount != null) Constants.LOCAL_FORMAT.format(fiatAmount.toNeutralFiat()).toString() else ""
        val fiatSymbol = if (fiatAmount != null) GenericUtils.currencySymbol(fiatAmount.currencyCode) else ""
        _uiState.update {
            it.copy(
                amountStr = amountStr,
                fiatAmountStr = fiatAmountStr,
                fiatSymbol = fiatSymbol,
                fromShieldedBalance = cost.fromShieldedBalance
            )
        }
    }
}
