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

package de.schildbach.wallet.ui.payments.internal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.FeeEstimate
import de.schildbach.wallet.service.platform.sdk.InternalTransferPair
import de.schildbach.wallet.service.platform.sdk.InternalTransferService
import de.schildbach.wallet.service.platform.sdk.InternalTransferSupport
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.defaultInternalTransferPair
import de.schildbach.wallet.service.platform.sdk.hasShieldedLeg
import de.schildbach.wallet.service.platform.sdk.internalTransferSupport
import de.schildbach.wallet.service.platform.sdk.pickerBalances
import de.schildbach.wallet.ui.payments.send.ExternalSendStatus
import de.schildbach.wallet.ui.shielded.parseDashOrNull
import de.schildbach.wallet.ui.shielded.parseDecimalOrNull
import de.schildbach.wallet.ui.shielded.toDashAt
import de.schildbach.wallet.ui.shielded.toFiatAt
import de.schildbach.wallet.ui.shielded.toKeypadString
import de.schildbach.wallet.ui.shielded.toKeypadStringTwoDecimals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.common.util.toFiat
import javax.inject.Inject

/** Which balance picker sheet is open. */
enum class InternalTransferPicker { FROM, TO }

/**
 * The confirm sheet: [request] is exactly what Confirm sends. [fee] null with [feeLoading] false
 * means the fee cannot be known before the transfer (never fabricated). The fee is paid on top.
 */
data class InternalTransferConfirm(
    val request: InternalTransferRequest,
    val fee: FeeEstimate? = null,
    val feeLoading: Boolean = true
) {
    /** What leaves the From balance: amount plus fee, null while the fee is unknown. */
    val total: Dash? get() = fee?.let { request.amount + it.fee }
}

/**
 * Single UI state of the Advanced-mode Internal tab (MO-1125).
 *
 * @property shieldedEnabled `USE_KOTLIN_SDK_SHIELDED`; null = unknown (not read yet, or unreadable):
 *   Shielded pairs are disabled, but a chosen Shielded pair is kept.
 * @property hasIdentity null = unknown: Identity is hidden.
 * @property pairChosenByUser the user changed From/To; until then the default pair
 *   ([defaultInternalTransferPair]) follows the inputs as they arrive.
 * @property balance the From balance; null = unknown (never 0), and it blocks Continue.
 * @property maxSendable null = unknown: Max is unavailable and [balance] bounds the amount.
 * @property sendingHidden the user closed the sending overlay of a shielded leg; the transfer
 *   continues and the form stays disabled until its outcome. Reset when the status changes.
 * @property pinnedAmount the exact amount while [amountText] is only its rendering (see the
 *   External Send's `pinnedAmount`).
 * @property confirmRefused Confirm was refused because the captured request no longer fits the
 *   balance or pair (see [InternalTransferViewModel.confirmedRequest]); the sheet was closed and
 *   the form says so until the user edits it or continues again.
 */
data class InternalTransferUIState(
    val from: SendBalance = SendBalance.CORE,
    val to: SendBalance = SendBalance.SHIELDED,
    val pairChosenByUser: Boolean = false,
    val shieldedEnabled: Boolean? = null,
    val hasIdentity: Boolean? = null,
    val amountText: String = "0",
    val dashMode: Boolean = true,
    val maxAmount: Dash? = null,
    val pinnedAmount: Dash? = null,
    val fiatCode: String? = null,
    val rate: Fiat? = null,
    val balance: Dash? = null,
    val maxSendable: Dash? = null,
    val picker: InternalTransferPicker? = null,
    val confirm: InternalTransferConfirm? = null,
    val status: ExternalSendStatus = ExternalSendStatus.Idle,
    val sendingHidden: Boolean = false,
    val confirmRefused: Boolean = false
) {
    val pair: InternalTransferPair get() = InternalTransferPair(from, to)

    /** The form can be edited: no transfer running or unacknowledged. */
    val formEditable: Boolean get() = status == ExternalSendStatus.Idle

    /** The balances the pickers list, in order; Identity only for a user known to have one. */
    val balances: List<SendBalance> get() = pickerBalances(hasIdentity)

    val support: InternalTransferSupport
        get() = internalTransferSupport(from, to, shieldedEnabled == true, hasIdentity == true)

    /**
     * Whether picking [balance] in [picker] leads to a supported pair (picking the other side's
     * balance swaps them, AC4). The current selection is always pickable (a no-op).
     */
    fun pickable(picker: InternalTransferPicker, balance: SendBalance): Boolean {
        val result = when (picker) {
            InternalTransferPicker.FROM -> if (balance == from) return true else pair.withFrom(balance)
            InternalTransferPicker.TO -> if (balance == to) return true else pair.withTo(balance)
        }
        return internalTransferSupport(
            result.from,
            result.to,
            shieldedEnabled == true,
            hasIdentity == true
        ) == InternalTransferSupport.SUPPORTED
    }

    val isMax: Boolean get() = maxAmount != null

    /** The amount in Dash (fiat converted at [rate]); zero when unparseable or no rate. */
    val amount: Dash
        get() = maxAmount ?: pinnedAmount ?: if (dashMode) {
            parseDashOrNull(amountText) ?: Dash.ZERO
        } else {
            val code = fiatCode
            if (code == null) Dash.ZERO else parseDecimalOrNull(amountText)?.toFiat(code)?.toDashAt(rate) ?: Dash.ZERO
        }

    /** The amount in the other currency, '.'-separated for the amount page; null without a rate. */
    val secondaryAmountText: String?
        get() = if (dashMode) {
            amount.toFiatAt(rate)?.toKeypadStringTwoDecimals() ?: if (rate == null) null else "0"
        } else {
            amount.toKeypadString()
        }

    /** What the amount may not exceed: Max (balance minus fee) when known, else the balance. */
    val limit: Dash? get() = maxSendable ?: balance

    /** AC7: the amount is above Max; shown as "Insufficient balance" and blocks Continue. */
    val insufficientBalance: Boolean get() = limit?.let { amount.isGreaterThan(it) } == true

    val canContinue: Boolean
        get() = support == InternalTransferSupport.SUPPORTED &&
            amount.isPositive &&
            balance != null &&
            !insufficientBalance &&
            (dashMode || rate != null || pinnedAmount != null || maxAmount != null) &&
            formEditable
}

/**
 * The Advanced-mode Internal tab (MO-1125): pick From and To among the user's own balances, an
 * amount, then confirm → PIN/biometric (the fragment) → [InternalTransferExecutor]. Nothing
 * optimistic: the transfer state is mirrored from the executor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InternalTransferViewModel @Inject constructor(
    private val service: InternalTransferService,
    private val executor: InternalTransferExecutor,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesProvider,
    private val savedState: SavedStateHandle
) : ViewModel() {

    companion object {
        internal const val KEY_FROM = "internal_transfer_from"
        internal const val KEY_TO = "internal_transfer_to"
        internal const val KEY_PAIR_CHOSEN = "internal_transfer_pair_chosen"
        internal const val KEY_AMOUNT = "internal_transfer_amount"
        internal const val KEY_DASH_MODE = "internal_transfer_dash_mode"
        internal const val KEY_MAX_DUFFS = "internal_transfer_max_duffs"
        internal const val KEY_PINNED_DUFFS = "internal_transfer_pinned_duffs"

        private const val DASH_DECIMALS = 8
        private const val FIAT_DECIMALS = 2
    }

    private val _uiState = MutableStateFlow(InternalTransferUIState())
    val uiState: StateFlow<InternalTransferUIState> = _uiState.asStateFlow()

    private var balanceJob: Job? = null
    private var balanceGeneration = 0
    private var feeJob: Job? = null

    init {
        restore()

        service.observeShieldedEnabled()
            .onEach { enabled ->
                _uiState.update { it.copy(shieldedEnabled = enabled) }
                reconcilePair()
            }
            .launchIn(viewModelScope)

        service.observeHasIdentity()
            .onEach { has ->
                _uiState.update { it.copy(hasIdentity = has) }
                reconcilePair()
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { code -> _uiState.update { it.copy(fiatCode = code) } }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { rate -> onRateChanged(rate?.fiat) }
            .launchIn(viewModelScope)

        executor.state
            .onEach { execution ->
                val status = when (execution) {
                    InternalTransferExecution.Restoring -> ExternalSendStatus.Restoring
                    InternalTransferExecution.InterruptedByRestart ->
                        ExternalSendStatus.Done(ExternalSendResult.MayHaveGoneThrough, afterRestart = true)
                    InternalTransferExecution.Idle -> ExternalSendStatus.Idle
                    // An outcome being dismissed: no dialog, nothing can be sent yet.
                    InternalTransferExecution.Acknowledging -> ExternalSendStatus.Restoring
                    is InternalTransferExecution.Sending -> ExternalSendStatus.Sending(
                        hasShieldedLeg(execution.request.from, execution.request.to)
                    )
                    is InternalTransferExecution.Finished -> ExternalSendStatus.Done(execution.result)
                }
                _uiState.update {
                    it.copy(status = status, sendingHidden = it.sendingHidden && status is ExternalSendStatus.Sending)
                }
            }
            .launchIn(viewModelScope)

        restartBalance()
    }

    /** Process restore: the pair the user chose and the amount. */
    private fun restore() {
        val from = savedState.get<String>(KEY_FROM)?.let(::balanceOrNull)
        val to = savedState.get<String>(KEY_TO)?.let(::balanceOrNull)
        val chosen = savedState.get<Boolean>(KEY_PAIR_CHOSEN) == true && from != null && to != null && from != to
        _uiState.update {
            it.copy(
                from = if (chosen && from != null) from else it.from,
                to = if (chosen && to != null) to else it.to,
                pairChosenByUser = chosen,
                amountText = savedState.get<String>(KEY_AMOUNT) ?: "0",
                dashMode = savedState.get<Boolean>(KEY_DASH_MODE) ?: true,
                maxAmount = savedState.get<Long>(KEY_MAX_DUFFS)?.let(::Dash),
                pinnedAmount = savedState.get<Long>(KEY_PINNED_DUFFS)?.let(::Dash)
            )
        }
    }

    private fun balanceOrNull(name: String): SendBalance? = SendBalance.entries.firstOrNull { it.name == name }

    /**
     * Until the user changes the pair, follow the default as the flag and identity arrive. A
     * user's (or restored) pair is dropped for the default only on CONFIRMED absence: the user
     * has no identity ([InternalTransferUIState.hasIdentity] false) for an Identity pair, or the
     * shielded flag is read as off (false) for a Shielded pair. While either is unknown (null:
     * not read yet, or a read error) the pair is kept and Continue stays disabled.
     */
    private fun reconcilePair() {
        val state = _uiState.value
        if (state.pairChosenByUser) {
            val sides = setOf(state.from, state.to)
            val identityGone = SendBalance.IDENTITY in sides && state.hasIdentity == false
            val shieldedOff = SendBalance.SHIELDED in sides && state.shieldedEnabled == false
            if (!identityGone && !shieldedOff) return
            _uiState.update { it.copy(pairChosenByUser = false) }
            savedState[KEY_PAIR_CHOSEN] = false
        }
        setPair(defaultInternalTransferPair(state.shieldedEnabled == true, state.hasIdentity == true), byUser = false)
    }

    /**
     * A typed fiat amount stays authoritative (its Dash value follows the rate, as [amount]
     * computes it). A Max or pinned amount is authoritative in Dash: in fiat mode its text is
     * only a rendering, so it is re-rendered at the new rate.
     */
    private fun onRateChanged(rate: Fiat?) {
        _uiState.update {
            val coinAuthoritative = it.maxAmount != null || it.pinnedAmount != null
            if (!it.dashMode && coinAuthoritative && rate != null) {
                it.copy(rate = rate, amountText = displayTextFor(it.amount, dashMode = false, rate = rate))
            } else {
                it.copy(rate = rate)
            }
        }
        saveAmount()
    }

    // ── From / To ────────────────────────────────────────────────────────────

    fun onShowPicker(picker: InternalTransferPicker) {
        if (!_uiState.value.formEditable) return
        _uiState.update { it.copy(picker = picker) }
    }

    fun onDismissPicker() {
        _uiState.update { it.copy(picker = null) }
    }

    /** A pick in the open picker; picking the other side's balance swaps them (AC4). */
    fun onBalancePicked(balance: SendBalance) {
        val state = _uiState.value
        val picker = state.picker ?: return
        if (!state.formEditable) return
        if (balance !in state.balances || !state.pickable(picker, balance)) return
        val pair = when (picker) {
            InternalTransferPicker.FROM -> state.pair.withFrom(balance)
            InternalTransferPicker.TO -> state.pair.withTo(balance)
        }
        setPair(pair, byUser = true)
        _uiState.update { it.copy(picker = null) }
    }

    /** AC3: From and To exchange. */
    fun onSwap() {
        val state = _uiState.value
        if (!state.formEditable) return
        setPair(state.pair.swapped(), byUser = true)
    }

    private fun setPair(pair: InternalTransferPair, byUser: Boolean) {
        val state = _uiState.value
        val fromChanged = state.from != pair.from
        val changed = fromChanged || state.to != pair.to
        _uiState.update {
            it.copy(
                from = pair.from,
                to = pair.to,
                pairChosenByUser = it.pairChosenByUser || byUser,
                confirmRefused = if (changed) false else it.confirmRefused,
                maxAmount = if (changed) null else it.maxAmount,
                // A dropped Max keeps its exact amount (no longer Max): its text may be rounded fiat.
                pinnedAmount = if (changed && it.isMax) it.amount else it.pinnedAmount,
                balance = if (fromChanged) null else it.balance,
                maxSendable = if (changed) null else it.maxSendable
            )
        }
        savedState[KEY_FROM] = pair.from.name
        savedState[KEY_TO] = pair.to.name
        savedState[KEY_PAIR_CHOSEN] = _uiState.value.pairChosenByUser
        saveAmount()
        if (changed) restartBalance()
    }

    /** Observe the From balance and re-read Max for the pair on every balance change. */
    private fun restartBalance() {
        balanceJob?.cancel()
        val from = _uiState.value.from
        val to = _uiState.value.to
        val generation = ++balanceGeneration
        balanceJob = service.observeBalance(from)
            .onEach { balance ->
                if (generation == balanceGeneration) _uiState.update { it.copy(balance = balance) }
            }
            .mapLatest { balance -> if (balance == null) null else service.maxSendable(from, to) }
            .onEach { max ->
                if (generation == balanceGeneration) _uiState.update { it.copy(maxSendable = max) }
            }
            .launchIn(viewModelScope)
    }

    // ── Amount ───────────────────────────────────────────────────────────────

    fun onKeyInput(key: String) {
        val state = _uiState.value
        if (!state.formEditable) return
        // Editing a Max amount edits what is on show.
        val base = if (state.isMax) displayTextFor(state.amount, state.dashMode, state.rate) else state.amountText
        val decimals = if (state.dashMode) DASH_DECIMALS else FIAT_DECIMALS
        val text = processAmountKeyInput(base, key, decimals)
        if (text == state.amountText && !state.isMax) return
        _uiState.update { it.copy(amountText = text, maxAmount = null, pinnedAmount = null, confirmRefused = false) }
        saveAmount()
    }

    fun onCurrencySelected(dashMode: Boolean) {
        val state = _uiState.value
        if (state.dashMode == dashMode) return
        if (!dashMode && state.rate == null) return
        val amount = state.amount
        val text = if (amount.isZero) "0" else displayTextFor(amount, dashMode, state.rate)
        _uiState.update {
            it.copy(
                dashMode = dashMode,
                amountText = text,
                pinnedAmount = if (amount.isZero || state.isMax) null else amount,
                confirmRefused = false
            )
        }
        saveAmount()
    }

    /** AC6: Max = the From balance minus the fee, when the service knows it. */
    fun onMaxClick() {
        val state = _uiState.value
        val max = state.maxSendable ?: return
        if (!state.formEditable) return
        _uiState.update {
            it.copy(
                maxAmount = max,
                pinnedAmount = null,
                amountText = displayTextFor(max, it.dashMode, it.rate),
                confirmRefused = false
            )
        }
        saveAmount()
    }

    // ── Confirm and transfer ─────────────────────────────────────────────────

    fun onContinue() {
        val state = _uiState.value
        if (!state.canContinue) return
        // The SDK wallet is captured here, without suspending: the transfer is refused unless the
        // same wallet is still bound when it runs. The own destination is resolved by the service.
        val request = InternalTransferRequest(
            state.from,
            state.to,
            state.amount,
            state.isMax,
            service.currentWalletIdOrNull()
        )
        _uiState.update { it.copy(confirm = InternalTransferConfirm(request), picker = null, confirmRefused = false) }
        feeJob?.cancel()
        feeJob = viewModelScope.launch {
            val fee = service.estimateFee(request.from, request.to)
            _uiState.update { current ->
                val confirm = current.confirm
                if (confirm?.request == request) {
                    current.copy(confirm = confirm.copy(fee = fee, feeLoading = false))
                } else {
                    current
                }
            }
        }
    }

    fun onDismissConfirm() {
        feeJob?.cancel()
        _uiState.update { it.copy(confirm = null) }
    }

    /**
     * The request the confirm sheet shows, captured BEFORE authentication; null when none.
     *
     * The CAPTURED request is validated, not the live form (whose fiat amount follows the rate).
     * When it no longer validates the sheet is closed and [InternalTransferUIState.confirmRefused]
     * says why, so Confirm never does nothing silently.
     */
    fun confirmedRequest(): InternalTransferRequest? {
        val request = _uiState.value.confirm?.request ?: return null
        return request.takeIf { stillValid(it) }
    }

    /** Authenticated: run [request], unless it is no longer the request on the confirm sheet. */
    fun submit(request: InternalTransferRequest): Boolean {
        if (_uiState.value.confirm?.request != request) return false
        // The balance may have moved during authentication.
        if (!stillValid(request)) return false
        if (!executor.submit(request)) return false
        _uiState.update { it.copy(confirm = null) }
        return true
    }

    /**
     * Whether the captured [request] can still be sent: it is the selected, supported pair, the
     * form is idle, the same SDK wallet is bound, and the From balance is known and covers the
     * captured amount (Max when known, as for Continue). Otherwise the confirm sheet is closed
     * with [InternalTransferUIState.confirmRefused] set.
     */
    private fun stillValid(request: InternalTransferRequest): Boolean {
        val state = _uiState.value
        if (!state.formEditable) return false
        val limit = state.limit
        val valid = state.from == request.from &&
            state.to == request.to &&
            state.support == InternalTransferSupport.SUPPORTED &&
            request.amount.isPositive &&
            state.balance != null &&
            limit != null &&
            !request.amount.isGreaterThan(limit) &&
            service.currentWalletIdOrNull() == request.expectedWalletIdHex
        if (!valid) {
            feeJob?.cancel()
            _uiState.update { if (it.confirm?.request == request) it.copy(confirm = null, confirmRefused = true) else it }
        }
        return valid
    }

    /**
     * The user closed the outcome. Not sent → back to the same amount (Max chosen again
     * deliberately); any other outcome clears the amount for a new transfer (the tab stays).
     */
    fun onResultAcknowledged() {
        val status = _uiState.value.status as? ExternalSendStatus.Done ?: return
        if (status.result is ExternalSendResult.NotSent) {
            _uiState.update { it.copy(maxAmount = null, pinnedAmount = if (it.isMax) it.amount else it.pinnedAmount) }
        } else {
            _uiState.update { it.copy(amountText = "0", maxAmount = null, pinnedAmount = null) }
        }
        saveAmount()
        executor.acknowledge()
    }

    /** Close on the sending overlay: the transfer continues on the app scope. */
    fun onHideSending() {
        if (_uiState.value.status is ExternalSendStatus.Sending) _uiState.update { it.copy(sendingHidden = true) }
    }

    /** Back closes an open sheet; false when there is nothing to close. */
    fun onBack(): Boolean {
        val state = _uiState.value
        return when {
            state.confirm != null && state.status == ExternalSendStatus.Idle -> {
                onDismissConfirm()
                true
            }
            state.picker != null -> {
                onDismissPicker()
                true
            }
            else -> false
        }
    }

    private fun displayTextFor(amount: Dash, dashMode: Boolean, rate: Fiat?): String =
        if (dashMode) amount.toKeypadString() else amount.toFiatAt(rate)?.toKeypadStringTwoDecimals() ?: "0"

    private fun saveAmount() {
        val state = _uiState.value
        savedState[KEY_AMOUNT] = state.amountText
        savedState[KEY_DASH_MODE] = state.dashMode
        savedState[KEY_MAX_DUFFS] = state.maxAmount?.duffs
        savedState[KEY_PINNED_DUFFS] = state.pinnedAmount?.duffs
    }
}
