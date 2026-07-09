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
import de.schildbach.wallet.service.platform.sdk.ShieldFromWalletOutcome
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
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.common.util.toFiat
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Single UI state of the "Internal transfer" screen (Figma sections
 * 1746:18463 Dash Wallet → Shielded and 1746:18480 Shielded → Dash Wallet,
 * error states 1750:19287, confirmation overlays 1689:15082 / 1746:18481).
 */
data class ShieldedTransferUIState(
    val direction: ShieldedTransferDirection = ShieldedTransferDirection.ToShielded,
    /** Raw keypad text in the currently selected unit. */
    val amountText: String = "0",
    /** True when the primary entry unit is DASH, false when fiat. */
    val dashMode: Boolean = true,
    val fiatCode: String = "USD",
    /** Fiat value of 1 DASH; null while no exchange rate is known. */
    val rate: Fiat? = null,
    val walletBalance: Dash = Dash.ZERO,
    val shieldedBalance: Dash = Dash.ZERO,
    /** True once the shielded runtime bring-up succeeded. */
    val ready: Boolean = false,
    val readyCheckDone: Boolean = false,
    /**
     * True when the Dash Wallet → Shielded direction can fund from the
     * L1 balance ([ShieldedBalanceService.isWalletShieldingAvailable]'s
     * shadow-SPV parity gate). Gates [canContinue] for that direction.
     */
    val walletShieldingAvailable: Boolean = false,
    val showConfirm: Boolean = false,
    val submitState: ShieldedSubmitState = ShieldedSubmitState.Idle
) {
    /** The entered amount as Dash, or ZERO when unparseable. */
    val amount: Dash
        get() = if (dashMode) {
            parseDashOrNull(amountText) ?: Dash.ZERO
        } else {
            parseDecimalOrNull(amountText)?.toFiat(fiatCode)?.toDashAt(rate) ?: Dash.ZERO
        }

    /** The balance the transfer is drawn from. */
    val availableBalance: Dash
        get() = when (direction) {
            ShieldedTransferDirection.ToShielded -> walletBalance
            ShieldedTransferDirection.FromShielded -> shieldedBalance
        }

    val insufficientFunds: Boolean
        get() = amount.isGreaterThan(availableBalance)

    /** The L1-funding gate only applies to the Dash Wallet → Shielded direction. */
    val directionAvailable: Boolean
        get() = direction == ShieldedTransferDirection.FromShielded || walletShieldingAvailable

    val canContinue: Boolean
        get() = ready && directionAvailable && amount.isPositive && !insufficientFunds &&
            (dashMode || rate != null) &&
            // NotSent is provably pre-broadcast, so retrying is safe
            (submitState == ShieldedSubmitState.Idle || submitState is ShieldedSubmitState.NotSent)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShieldedTransferViewModel @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    private val walletDataProvider: WalletDataProvider,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesProvider
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(ShieldedTransferViewModel::class.java)
    }

    /** Test seam: spends block for a ~30s Halo 2 proof and must stay off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _uiState = MutableStateFlow(ShieldedTransferUIState())
    val uiState: StateFlow<ShieldedTransferUIState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val ready = shieldedBalanceService.ensureShieldedReady()
            val walletShielding = ready && shieldedBalanceService.isWalletShieldingAvailable()
            _uiState.value = _uiState.value.copy(
                ready = ready,
                readyCheckDone = true,
                walletShieldingAvailable = walletShielding
            )
        }

        shieldedBalanceService.observeShieldedBalance()
            .onEach { _uiState.value = _uiState.value.copy(shieldedBalance = it) }
            .launchIn(viewModelScope)

        walletDataProvider.observeTotalBalance()
            .onEach { _uiState.value = _uiState.value.copy(walletBalance = Dash(it.value)) }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { code -> _uiState.value = _uiState.value.copy(fiatCode = code) }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { rate -> _uiState.value = _uiState.value.copy(rate = rate?.fiat) }
            .launchIn(viewModelScope)
    }

    /** Clears per-visit state; called when the screen is (re)entered. */
    fun reset(direction: ShieldedTransferDirection = ShieldedTransferDirection.ToShielded) {
        _uiState.value = _uiState.value.copy(
            direction = direction,
            amountText = "0",
            dashMode = true,
            showConfirm = false,
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
            // typing again clears an inline "not sent" error
            submitState = ShieldedSubmitState.Idle
        )
    }

    fun onMaxClick() {
        val state = _uiState.value
        val max = state.availableBalance
        val text = if (state.dashMode) {
            max.toKeypadString()
        } else {
            max.toFiatAt(state.rate)?.toPlainString() ?: return
        }
        _uiState.value = state.copy(amountText = text, submitState = ShieldedSubmitState.Idle)
    }

    /** Toggle DASH ↔ fiat entry, converting the current amount (Figma "Enter in fiat"). */
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

    /** The btn-reverse between the From/To rows (Figma 1687:13660). */
    fun onSwapDirection() {
        val state = _uiState.value
        val direction = when (state.direction) {
            ShieldedTransferDirection.ToShielded -> ShieldedTransferDirection.FromShielded
            ShieldedTransferDirection.FromShielded -> ShieldedTransferDirection.ToShielded
        }
        _uiState.value = state.copy(direction = direction, submitState = ShieldedSubmitState.Idle)
    }

    fun onContinue() {
        if (_uiState.value.canContinue) {
            _uiState.value = _uiState.value.copy(showConfirm = true)
        }
    }

    fun onDismissConfirm() {
        _uiState.value = _uiState.value.copy(showConfirm = false)
    }

    /**
     * Runs the spend. "Dash Wallet → Shielded" is the L1 asset-lock
     * pipeline ([ShieldedBalanceService.shieldFromWallet]); "Shielded →
     * Dash Wallet" is the Type 19 withdraw. Maps the SdkWriteResult
     * contract onto the UI: Broadcast → [ShieldedSubmitState.Success]
     * (or [ShieldedSubmitState.LockedPendingShield] when the lock is out
     * but the shield transition awaits its automatic retry); NotBroadcast
     * → [ShieldedSubmitState.NotSent] (retry allowed); Ambiguous →
     * [ShieldedSubmitState.MayHaveGoneThrough] (terminal — never retried).
     */
    fun onConfirm() {
        val state = _uiState.value
        if (!state.canContinue || !state.showConfirm) return
        val amount = state.amount
        val direction = state.direction
        _uiState.value = state.copy(showConfirm = false, submitState = ShieldedSubmitState.Proving)

        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                try {
                    when (direction) {
                        ShieldedTransferDirection.ToShielded ->
                            shieldedBalanceService.shieldFromWallet(amount)
                        ShieldedTransferDirection.FromShielded ->
                            shieldedBalanceService.withdrawToCore(
                                walletDataProvider.freshReceiveAddressString(),
                                amount
                            )
                    }
                } catch (e: Exception) {
                    // freshReceiveAddress failures happen strictly pre-broadcast
                    SdkWriteResult.NotBroadcast("failed to prepare transfer", e)
                }
            }
            _uiState.value = _uiState.value.copy(
                submitState = when (result) {
                    is SdkWriteResult.Broadcast -> when (result.value) {
                        ShieldFromWalletOutcome.SHIELD_PENDING_RETRY -> {
                            log.warn("wallet shield locked but pending — surfacing auto-retry state")
                            ShieldedSubmitState.LockedPendingShield
                        }
                        else -> ShieldedSubmitState.Success
                    }
                    is SdkWriteResult.NotBroadcast -> {
                        log.info("shielded transfer not sent: {}", result.reason)
                        ShieldedSubmitState.NotSent(result.reason)
                    }
                    is SdkWriteResult.Ambiguous -> {
                        log.warn("shielded transfer ambiguous — surfacing terminal state")
                        ShieldedSubmitState.MayHaveGoneThrough
                    }
                }
            )
        }
    }

    /** Dismisses a terminal result overlay (Success / MayHaveGoneThrough). */
    fun onResultHandled() {
        _uiState.value = _uiState.value.copy(
            submitState = ShieldedSubmitState.Idle,
            amountText = "0"
        )
    }
}
