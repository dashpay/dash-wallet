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
import de.schildbach.wallet.service.platform.sdk.ContestedUsernameFees
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.shieldedIdentityFundingRequirement
import de.schildbach.wallet.ui.username.UsernameType
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.GenericUtils
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class ConfirmUserNameUIState(
    val amountStr: String = "",
    val fiatSymbol: String = "",
    val fiatAmountStr: String = "",
    /** The shown amount leaves the SHIELDED pool — render the source label. */
    val fromShieldedBalance: Boolean = false,
    /**
     * [amountStr] is the DASH amount the confirm tap actually incurs: computed
     * from the dialog's applied [ConfirmUserNameInputs] and the resolved
     * contested fee pair. Until then confirmation must stay unavailable — the
     * user would otherwise approve an amount that is blank or computed for the
     * wrong username kind. Fiat data plays no part: [fiatAmountStr] is
     * decoration and may stay blank (no selected currency, no rate).
     */
    val amountReady: Boolean = false,
    val usernameSubmittedSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false
)

/** The dialog inputs the confirmation amount depends on — see [resolveUsernameConfirmCost]. */
data class ConfirmUserNameInputs(
    val usernameType: UsernameType,
    val isContestableUsername: Boolean,
    val hasIdentity: Boolean,
    /**
     * The balance paying for the creation. The dialog always shows the
     * amount actually withdrawn from the user's wallet: on the shielded
     * path that is the whole Type-20 exit denomination leaving the pool
     * (0.03 non-contested / 0.25 contested under v13), not the underlying fee.
     */
    val paymentSource: UsernamePaymentSource
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
 *   leaves the shielded pool (0.03 non-contested / 0.25 contested under
 *   v13) — shown here, the conceptual "paid" step.
 * - Primary L1 paths keep the fee schedule (0.25 contested / 0.03
 *   otherwise; contested-name-only registration from identity credits
 *   keeps its own fee).
 */
internal fun resolveUsernameConfirmCost(
    usernameType: UsernameType,
    isContestable: Boolean,
    hasIdentity: Boolean,
    paymentSource: UsernamePaymentSource,
    contestedFees: ContestedUsernameFees
): UsernameConfirmCost = when {
    // The instant/secondary name is always free — the identity funding is
    // disclosed on the primary confirm and is unchanged by adding it.
    usernameType == UsernameType.Secondary ->
        UsernameConfirmCost(Coin.ZERO, fromShieldedBalance = false)
    paymentSource == UsernamePaymentSource.SHIELDED_BALANCE -> {
        val fee = if (isContestable) contestedFees.contested else Constants.DASH_PAY_FEE
        val denomination = shieldedIdentityFundingRequirement(Dash(fee.value))
            ?.let { Coin.valueOf(it.duffs) }
            ?: fee
        UsernameConfirmCost(denomination, fromShieldedBalance = true)
    }
    isContestable && hasIdentity ->
        UsernameConfirmCost(contestedFees.contestedName, fromShieldedBalance = false)
    isContestable ->
        UsernameConfirmCost(contestedFees.contested, fromShieldedBalance = false)
    else ->
        UsernameConfirmCost(Constants.DASH_PAY_FEE, fromShieldedBalance = false)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConfirmUserNameDialogViewModel @Inject constructor(
    var configuration: Configuration,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    private val walletUIConfig: WalletUIConfig,
    private val dashSdkService: DashSdkService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfirmUserNameUIState())
    val uiState: StateFlow<ConfirmUserNameUIState> = _uiState.asStateFlow()

    /**
     * The dialog's inputs; null until the fragment applies them with
     * [setInputs]. Reactive on purpose: the amount must be recomputed when
     * they land, even when every other input (fees, a cached exchange rate)
     * emitted first — otherwise the default primary, non-contested cost
     * could be shown and approved.
     */
    private val _inputs = MutableStateFlow<ConfirmUserNameInputs?>(null)

    /**
     * Protocol-gated contested fee (MO-1069), resolved once from a
     * [DashSdkService.currentProtocolVersion] read; null until that read
     * lands, so the confirmation amount is never computed from a placeholder
     * fee pair.
     */
    private val _contestedFees = MutableStateFlow<ContestedUsernameFees?>(null)

    /**
     * The selected currency's exchange rate, or null without one. Starts with
     * null so the DASH amount never waits on fiat data: the currency
     * preference and the rate cache may both be empty.
     */
    private val exchangeRate: Flow<ExchangeRate?> =
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .flatMapLatest { code ->
                if (code == null) flowOf(null) else exchangeRatesProvider.observeExchangeRate(code)
            }
            .onStart { emit(null) }
            .catch { e ->
                log.warn("exchange rate unavailable; showing the DASH amount only", e)
                emit(null)
            }

    init {
        viewModelScope.launch {
            _contestedFees.value = try {
                ContestedUsernameFees.current(dashSdkService)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // current() already maps an unknown version to LEGACY; this only
                // guards the dialog against a never-resolving amount.
                log.warn("contested fee lookup failed; showing the legacy fee", e)
                ContestedUsernameFees.LEGACY
            }
        }

        combine(
            _inputs.filterNotNull(),
            _contestedFees.filterNotNull(),
            exchangeRate
        ) { inputs, fees, rate -> Triple(inputs, fees, rate) }
            .onEach { (inputs, fees, rate) -> updateAmount(inputs, fees, rate) }
            .launchIn(viewModelScope)
    }

    /**
     * Apply the dialog's inputs. The amount is (re)computed from them as soon
     * as the contested fee pair is resolved; confirmation stays unavailable
     * ([ConfirmUserNameUIState.amountReady] false) until then.
     */
    fun setInputs(inputs: ConfirmUserNameInputs) {
        _inputs.value = inputs
    }

    private fun updateAmount(inputs: ConfirmUserNameInputs, fees: ContestedUsernameFees, rate: ExchangeRate?) {
        val cost = resolveUsernameConfirmCost(
            inputs.usernameType,
            inputs.isContestableUsername,
            inputs.hasIdentity,
            inputs.paymentSource,
            fees
        )
        val amountStr = MonetaryFormat.BTC.noCode().format(cost.amount.toNeutralCoin()).toString()

        val fiatAmount = rate?.let { exchangeRateData ->
            try {
                org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRateData.fiat.toDashjFiat())
                    .coinToFiat(cost.amount)
            } catch (e: Exception) {
                // A malformed cached rate must not hide the DASH amount.
                log.warn("could not convert the confirmation amount to fiat", e)
                null
            }
        }
        val fiatAmountStr = if (fiatAmount != null) {
            Constants.LOCAL_FORMAT.format(fiatAmount.toNeutralFiat()).toString()
        } else {
            ""
        }
        val fiatSymbol = if (fiatAmount != null) GenericUtils.currencySymbol(fiatAmount.currencyCode) else ""
        _uiState.update {
            it.copy(
                amountStr = amountStr,
                fiatAmountStr = fiatAmountStr,
                fiatSymbol = fiatSymbol,
                fromShieldedBalance = cost.fromShieldedBalance,
                amountReady = true
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfirmUserNameDialogViewModel::class.java)
    }
}
