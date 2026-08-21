/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.shielded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.common.util.toFiat
import org.slf4j.LoggerFactory
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

/**
 * Single UI state of the shielded "Send to address" flow (the Send tab of
 * Figma 1684:12990) — a shielded → shielded transfer (Type 16) to a pasted
 * or scanned bech32m address.
 */
data class ShieldedSendUIState(
    val address: String = "",
    val amountText: String = "0",
    val dashMode: Boolean = true,
    val fiatCode: String = "USD",
    val rate: Fiat? = null,
    val shieldedBalance: Dash = Dash.ZERO,
    val ready: Boolean = false,
    val readyCheckDone: Boolean = false,
    val showConfirm: Boolean = false,
    val submitState: ShieldedSubmitState = ShieldedSubmitState.Idle
) {
    val amount: Dash
        get() = if (dashMode) {
            parseDashOrNull(amountText) ?: Dash.ZERO
        } else {
            parseDecimalOrNull(amountText)?.toFiat(fiatCode)?.toDashAt(rate) ?: Dash.ZERO
        }

    /** Light-weight shape check; the service re-validates before broadcast. */
    val addressLooksValid: Boolean
        get() = address.trim().lowercase().let { it.startsWith("dash1") || it.startsWith("tdash1") }

    val insufficientFunds: Boolean
        get() = amount.isGreaterThan(shieldedBalance)

    val canContinue: Boolean
        get() = ready && addressLooksValid && amount.isPositive && !insufficientFunds &&
            (dashMode || rate != null) &&
            (submitState == ShieldedSubmitState.Idle || submitState is ShieldedSubmitState.NotSent)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShieldedSendViewModel @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesProvider
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(ShieldedSendViewModel::class.java)
    }

    /** Test seam: spends block for a ~30s Halo 2 proof and must stay off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _uiState = MutableStateFlow(ShieldedSendUIState())
    val uiState: StateFlow<ShieldedSendUIState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val ready = shieldedBalanceService.ensureShieldedReady()
            _uiState.value = _uiState.value.copy(ready = ready, readyCheckDone = true)
        }

        shieldedBalanceService.observeShieldedBalance()
            .onEach { _uiState.value = _uiState.value.copy(shieldedBalance = it) }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { code -> _uiState.value = _uiState.value.copy(fiatCode = code) }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { rate -> _uiState.value = _uiState.value.copy(rate = rate?.fiat) }
            .launchIn(viewModelScope)
    }

    /** Clears per-visit state; [prefillAddress] comes from the QR scanner. */
    fun reset(prefillAddress: String? = null) {
        _uiState.value = _uiState.value.copy(
            address = prefillAddress?.trim().orEmpty(),
            amountText = "0",
            dashMode = true,
            showConfirm = false,
            submitState = ShieldedSubmitState.Idle
        )
    }

    fun onAddressChanged(address: String) {
        _uiState.value = _uiState.value.copy(
            address = address,
            submitState = ShieldedSubmitState.Idle
        )
    }

    fun onKeyInput(key: String) {
        val state = _uiState.value
        if (state.submitState != ShieldedSubmitState.Idle &&
            state.submitState !is ShieldedSubmitState.NotSent
        ) {
            return
        }
        val maxDecimals = if (state.dashMode) 8 else 2
        _uiState.value = state.copy(
            amountText = processAmountKeyInput(state.amountText, key, maxDecimals),
            submitState = ShieldedSubmitState.Idle
        )
    }

    fun onMaxClick() {
        val state = _uiState.value
        val text = if (state.dashMode) {
            state.shieldedBalance.toKeypadString()
        } else {
            state.shieldedBalance.toFiatAt(state.rate)?.toPlainString() ?: return
        }
        _uiState.value = state.copy(amountText = text, submitState = ShieldedSubmitState.Idle)
    }

    fun onCurrencySelected(dashMode: Boolean) {
        val state = _uiState.value
        if (state.dashMode == dashMode) return
        val amount = state.amount
        val text = when {
            amount.isZero -> "0"
            dashMode -> amount.toKeypadString()
            else -> amount.toFiatAt(state.rate)?.toPlainString() ?: "0"
        }
        _uiState.value = state.copy(dashMode = dashMode, amountText = text)
    }

    fun onContinue() {
        if (_uiState.value.canContinue) {
            _uiState.value = _uiState.value.copy(showConfirm = true)
        }
    }

    fun onDismissConfirm() {
        _uiState.value = _uiState.value.copy(showConfirm = false)
    }

    /** Same SdkWriteResult mapping as the internal transfer — see [ShieldedTransferViewModel.onConfirm]. */
    fun onConfirm() {
        val state = _uiState.value
        if (!state.canContinue || !state.showConfirm) return
        val address = state.address.trim()
        val amount = state.amount
        _uiState.value = state.copy(showConfirm = false, submitState = ShieldedSubmitState.Proving)

        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                shieldedBalanceService.transferShielded(address, amount, memo = null)
            }
            _uiState.value = _uiState.value.copy(
                submitState = when (result) {
                    is SdkWriteResult.Broadcast -> ShieldedSubmitState.Success
                    is SdkWriteResult.NotBroadcast -> {
                        log.info("shielded send not sent: {}", result.reason)
                        ShieldedSubmitState.NotSent(result.reason)
                    }
                    is SdkWriteResult.Ambiguous -> {
                        log.warn("shielded send ambiguous — surfacing terminal state")
                        ShieldedSubmitState.MayHaveGoneThrough
                    }
                }
            )
        }
    }

    fun onResultHandled() {
        _uiState.value = _uiState.value.copy(
            submitState = ShieldedSubmitState.Idle,
            amountText = "0"
        )
    }
}
