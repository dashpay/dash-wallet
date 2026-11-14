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

package de.schildbach.wallet.ui.payments.send

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.DestinationParse
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.ExternalSendService
import de.schildbach.wallet.service.platform.sdk.ExternalSendServiceImpl
import de.schildbach.wallet.service.platform.sdk.FeeEstimate
import de.schildbach.wallet.service.platform.sdk.IdentityResolution
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendDestination
import de.schildbach.wallet.service.platform.sdk.SendSupport
import de.schildbach.wallet.service.platform.sdk.WITHDRAWAL_CORE_FEE_DUFFS
import de.schildbach.wallet.service.platform.sdk.balanceKindOf
import de.schildbach.wallet.service.platform.sdk.detectSendDestination
import de.schildbach.wallet.service.platform.sdk.externalSendSupport
import de.schildbach.wallet.service.platform.sdk.hasShieldedLeg
import de.schildbach.wallet.service.platform.sdk.maxBoundsAmount
import de.schildbach.wallet.service.platform.sdk.pickerBalances
import de.schildbach.wallet.ui.shielded.parseDashOrNull
import de.schildbach.wallet.ui.shielded.parseDecimalOrNull
import de.schildbach.wallet.ui.shielded.toDashAt
import de.schildbach.wallet.ui.shielded.toFiatAt
import de.schildbach.wallet.ui.shielded.toKeypadString
import de.schildbach.wallet.ui.shielded.toKeypadStringTwoDecimals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.common.util.toFiat
import javax.inject.Inject

private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** The two pages of the external send flow. */
enum class ExternalSendStep { ADDRESS, AMOUNT }

/** What the screen shows for the external send the [ExternalSendExecutor] runs. */
sealed class ExternalSendStatus {
    /** Whether a previous process left a send in flight is still being read; nothing can be sent. */
    object Restoring : ExternalSendStatus()

    object Idle : ExternalSendStatus()

    /** In flight; [shieldedLeg] = a ~30 s proof is involved. */
    data class Sending(val shieldedLeg: Boolean) : ExternalSendStatus()

    /**
     * Terminal until acknowledged. [afterRestart]: the app was closed while a send may have
     * been in flight ([result] is then [ExternalSendResult.MayHaveGoneThrough]).
     */
    data class Done(val result: ExternalSendResult, val afterRestart: Boolean = false) : ExternalSendStatus()
}

/**
 * The confirm sheet: [request] is exactly what Confirm sends. [fee] null with [feeLoading] false
 * means the fee cannot be known before the send (never fabricated).
 */
data class ExternalSendConfirm(
    val request: ExternalSendRequest,
    val fee: FeeEstimate? = null,
    val feeLoading: Boolean = true
) {
    /**
     * A Core → Core Max is the SDK drain: the fee comes OUT of [ExternalSendRequest.amount]
     * (the Basic send's `sendAllBalance` semantics: delivered = amount − fee, total = amount).
     */
    val feeIncluded: Boolean
        get() = request.isMax && request.source == SendBalance.CORE && request.destination is SendDestination.Core

    /**
     * A Shielded → Core Max withdraws [ExternalSendRequest.amount] itself, and DPP takes the asset
     * unlock's Core fee ([WITHDRAWAL_CORE_FEE_DUFFS]) out of it: the recipient receives the amount
     * minus that fee. Every other withdrawal to Core withdraws the fee on top of the amount.
     */
    val coreFeeIncluded: Boolean
        get() = request.isMax && request.source == SendBalance.SHIELDED && request.destination is SendDestination.Core

    /**
     * What the recipient receives: the amount; for a drain the amount minus the fee (null while
     * the fee is loading or unknown: the gross amount would overstate it); for a Shielded → Core
     * Max the amount minus the Core fee.
     */
    val recipientAmount: Dash?
        get() = when {
            feeIncluded -> fee?.let { request.amount - it.fee }
            coreFeeIncluded -> request.amount - Dash(WITHDRAWAL_CORE_FEE_DUFFS)
            else -> request.amount
        }

    /**
     * What leaves the balance: what the recipient receives plus the fee (null while the fee is
     * unknown), or for a drain the amount.
     */
    val total: Dash?
        get() = if (feeIncluded) request.amount else recipientAmount?.let { amount -> fee?.let { amount + it.fee } }
}

/**
 * Single UI state of the Advanced-mode external send (MO-1121).
 *
 * @property balance the selected [source]'s spendable balance; null = unknown (shown as a
 *   placeholder, never as 0, and it blocks Continue).
 * @property maxSendable null = unknown: Max is unavailable and the [balance] bounds the amount.
 * @property maxPending Max was set when the source changed: it follows the new [source] once
 *   that source's [maxSendable] is known (or is dropped when it has none). Meanwhile the amount
 *   is zero, so the old source's Max can never be confirmed against the new source.
 * @property pinnedAmount the exact amount while [amountText] is only its rendering (a currency
 *   switch rounds fiat to two decimals, a dropped Max leaves its text): never re-parsed until
 *   the user edits the amount (see `ShieldedTransferViewModel`'s pinnedAmount).
 * @property identityResolution for an identity destination: null while checking.
 * @property sourceChosenByUser the user picked [source] in the sheet; until then the first
 *   supported source is chosen automatically as the inputs arrive.
 * @property finished the user acknowledged a terminal outcome other than not-sent: the flow is
 *   over and the form, which still holds the sent amount, stays inert while the screen leaves
 *   (the executor returning to Idle must not re-enable it).
 */
data class ExternalSendUIState(
    val step: ExternalSendStep = ExternalSendStep.ADDRESS,
    val addressInput: String = "",
    val addressParse: DestinationParse = DestinationParse.Empty,
    val clipboardShown: Boolean = false,
    val clipboardEntries: List<String> = emptyList(),
    val destination: SendDestination? = null,
    val amountText: String = "0",
    val dashMode: Boolean = true,
    val maxAmount: Dash? = null,
    val maxPending: Boolean = false,
    val pinnedAmount: Dash? = null,
    val fiatCode: String? = null,
    val rate: Fiat? = null,
    val source: SendBalance = SendBalance.CORE,
    val sourceChosenByUser: Boolean = false,
    val hasIdentity: Boolean? = null,
    val destinationIsOwnIdentity: Boolean? = null,
    val identityResolution: IdentityResolution? = null,
    val balance: Dash? = null,
    val maxSendable: Dash? = null,
    val showSourceSheet: Boolean = false,
    val confirm: ExternalSendConfirm? = null,
    val status: ExternalSendStatus = ExternalSendStatus.Idle,
    val finished: Boolean = false
) {
    /**
     * The txid of a sent outcome that is shown by the transaction result screen (the fragment
     * opens it once, then acknowledges) instead of the in-screen result. Null for every other
     * outcome, for a Sent without a well-formed txid (64 hex characters, so `Sha256Hash.wrap`
     * cannot throw), and once acknowledged ([finished]).
     */
    val openTransactionResult: String?
        get() = if (finished) {
            null
        } else {
            ((status as? ExternalSendStatus.Done)?.result as? ExternalSendResult.Sent)?.txIdOrNull
                ?.takeIf { txId -> txId.length == 64 && txId.all { it.isHexDigitChar() } }
        }

    /** The form can be edited: no send running or unacknowledged, and the flow not over. */
    val formEditable: Boolean
        get() = status == ExternalSendStatus.Idle && !finished

    /** A non-blank input that is not a destination of this network. */
    val addressError: Boolean
        get() = addressParse is DestinationParse.WrongNetwork || addressParse is DestinationParse.Invalid

    val canContinueAddress: Boolean
        get() = addressParse is DestinationParse.Valid && !finished

    /** The sources the sheet lists, in order; Identity only for a user who has one. */
    val sources: List<SendBalance>
        get() = pickerBalances(hasIdentity)

    /** Whether [source] can pay [destination]; null before a destination is set. */
    fun supportOf(source: SendBalance): SendSupport? = destination?.let {
        externalSendSupport(source, it, hasIdentity == true, destinationIsOwnIdentity)
    }

    /** Max is set: the exact [maxAmount], whatever the currency shown. */
    val isMax: Boolean
        get() = maxAmount != null

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

    /**
     * What the amount may not exceed: the max sendable when known, else the balance; for a
     * pair whose Max is its bound ([maxBoundsAmount]) the max sendable only (null blocks).
     */
    val limit: Dash?
        get() = if (maxIsTheBound) maxSendable else maxSendable ?: balance

    private val maxIsTheBound: Boolean
        get() = destination?.let { maxBoundsAmount(source, balanceKindOf(it)) } == true

    val exceedsBalance: Boolean
        get() = limit?.let { amount.isGreaterThan(it) } == true

    /** An identity destination must be confirmed to exist (AC8); NotFound or Unknown blocks. */
    val identityBlocks: Boolean
        get() = destination is SendDestination.Identity && identityResolution !is IdentityResolution.Found

    val canContinueAmount: Boolean
        get() = step == ExternalSendStep.AMOUNT &&
            destination != null &&
            supportOf(source) == SendSupport.SUPPORTED &&
            amount.isPositive &&
            !maxPending &&
            balance != null &&
            limit != null &&
            !exceedsBalance &&
            (dashMode || rate != null || pinnedAmount != null || maxAmount != null) &&
            !identityBlocks &&
            formEditable
}

/**
 * The Advanced-mode external send flow (MO-1121): address, then amount and source, then
 * confirm → PIN/biometric (the fragment) → [ExternalSendExecutor]. Nothing optimistic: the send
 * state is mirrored from the executor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExternalSendViewModel internal constructor(
    private val service: ExternalSendService,
    private val executor: ExternalSendExecutor,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesProvider,
    private val savedState: SavedStateHandle,
    private val network: AddressNetwork,
    private val maxRetryDelayMs: Long = ExternalSendServiceImpl.BALANCE_POLL_MS
) : ViewModel() {

    @Inject
    constructor(
        service: ExternalSendService,
        executor: ExternalSendExecutor,
        walletUIConfig: WalletUIConfig,
        exchangeRates: ExchangeRatesProvider,
        savedState: SavedStateHandle
    ) : this(
        service,
        executor,
        walletUIConfig,
        exchangeRates,
        savedState,
        AddressNetwork.fromId(Constants.NETWORK_PARAMETERS.id)
    )

    companion object {
        /** Nav argument: a scanned/typed destination (address, identity id or `dash:` URI). */
        const val ARG_PREFILL = "prefill"

        internal const val KEY_SEEDED = "external_send_seeded"
        internal const val KEY_ADDRESS = "external_send_address"
        internal const val KEY_STEP = "external_send_step"
        internal const val KEY_AMOUNT = "external_send_amount"
        internal const val KEY_DASH_MODE = "external_send_dash_mode"
        internal const val KEY_MAX_DUFFS = "external_send_max_duffs"
        internal const val KEY_MAX_PENDING = "external_send_max_pending"
        internal const val KEY_PINNED_DUFFS = "external_send_pinned_duffs"
        internal const val KEY_SOURCE = "external_send_source"
        internal const val KEY_SOURCE_CHOSEN = "external_send_source_chosen"
        internal const val KEY_FINISHED = "external_send_finished"

        private const val DASH_DECIMALS = 8
        private const val FIAT_DECIMALS = 2
        private const val MAX_CLIPBOARD_ENTRIES = 3

        /** "tdash1z...lrs27eg4" — the first 7 and last 8 characters of a long address or id. */
        fun shorten(value: String, head: Int = 7, tail: Int = 8): String =
            if (value.length <= head + tail + 3) value else "${value.take(head)}...${value.takeLast(tail)}"
    }

    private val _uiState = MutableStateFlow(ExternalSendUIState())
    val uiState: StateFlow<ExternalSendUIState> = _uiState.asStateFlow()

    /**
     * [ExternalSendUIState.openTransactionResult] as its own flow, for the fragment's
     * resumed-and-unlocked delivery. It may lag [uiState] by a dispatch; [takeTransactionResult]
     * checks the live state.
     */
    val openTransactionResult: StateFlow<String?> = _uiState
        .map { it.openTransactionResult }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var balanceJob: Job? = null
    private var balanceGeneration = 0
    private var identityJob: Job? = null
    private var ownIdentityJob: Job? = null
    private var feeJob: Job? = null

    init {
        restoreOrSeed()
        if (savedState.get<Boolean>(KEY_FINISHED) == true) {
            _uiState.update { it.copy(finished = true) }
        }

        service.observeHasIdentity()
            .onEach { has ->
                _uiState.update { it.copy(hasIdentity = has) }
                autoPickSource()
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { code -> _uiState.update { it.copy(fiatCode = code) } }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { rate -> _uiState.update { it.copy(rate = rate?.fiat) } }
            .launchIn(viewModelScope)

        // Finish an interrupted Core → Platform send, if any (background, app-scoped).
        executor.resumePendingPlatformFunding()

        executor.state
            .onEach { execution ->
                val status = when (execution) {
                    ExternalSendExecution.Restoring -> ExternalSendStatus.Restoring
                    ExternalSendExecution.InterruptedByRestart ->
                        ExternalSendStatus.Done(ExternalSendResult.MayHaveGoneThrough, afterRestart = true)
                    ExternalSendExecution.Idle -> ExternalSendStatus.Idle
                    // An outcome being dismissed: no dialog, nothing can be sent yet.
                    ExternalSendExecution.Acknowledging -> ExternalSendStatus.Restoring
                    is ExternalSendExecution.Sending -> ExternalSendStatus.Sending(
                        hasShieldedLeg(execution.request.source, balanceKindOf(execution.request.destination))
                    )
                    is ExternalSendExecution.Finished -> ExternalSendStatus.Done(execution.result)
                }
                _uiState.update { it.copy(status = status) }
            }
            .launchIn(viewModelScope)
    }

    private fun restoreOrSeed() {
        if (savedState.get<Boolean>(KEY_SEEDED) != true) {
            savedState[KEY_SEEDED] = true
            val prefill = savedState.get<String>(ARG_PREFILL)
            if (!prefill.isNullOrBlank()) {
                onAddressChanged(prefill)
                val parse = _uiState.value.addressParse
                if (parse is DestinationParse.Valid) {
                    enterAmountStep(parse.destination, parse.amount)
                }
            }
            return
        }
        // Process restore: rebuild from what was saved.
        val address = savedState.get<String>(KEY_ADDRESS).orEmpty()
        onAddressChanged(address)
        val parse = _uiState.value.addressParse
        if (savedState.get<String>(KEY_STEP) == ExternalSendStep.AMOUNT.name && parse is DestinationParse.Valid) {
            val source = savedState.get<String>(KEY_SOURCE)
                ?.let { name -> SendBalance.entries.firstOrNull { it.name == name } }
            val chosen = savedState.get<Boolean>(KEY_SOURCE_CHOSEN) == true && source != null
            // Read before enterAmountStep, which saves its own (reset) amount.
            val amountText = savedState.get<String>(KEY_AMOUNT) ?: "0"
            val dashMode = savedState.get<Boolean>(KEY_DASH_MODE) ?: true
            val maxAmount = savedState.get<Long>(KEY_MAX_DUFFS)?.let(::Dash)
            val maxPending = maxAmount == null && savedState.get<Boolean>(KEY_MAX_PENDING) == true
            val pinnedAmount = savedState.get<Long>(KEY_PINNED_DUFFS)?.let(::Dash)
            enterAmountStep(parse.destination, amount = null)
            if (chosen && source != null) {
                selectSource(source, byUser = true)
            }
            // After the source: restoring the source must not turn the saved Max into a pending one.
            _uiState.update {
                it.copy(
                    amountText = amountText,
                    dashMode = dashMode,
                    maxAmount = maxAmount,
                    maxPending = maxPending,
                    pinnedAmount = pinnedAmount
                )
            }
            saveAmount()
            // The source's Max may already have been read before the pending Max was restored:
            // read it again so the restored pending Max resolves.
            if (maxPending) restartBalance()
        }
    }

    // ── Address step ─────────────────────────────────────────────────────────

    fun onAddressChanged(input: String) {
        val parse = detectSendDestination(input, network)
        _uiState.update {
            it.copy(
                addressInput = input,
                addressParse = parse,
                clipboardShown = it.clipboardShown && input.isEmpty()
            )
        }
        savedState[KEY_ADDRESS] = input
    }

    /**
     * The user tapped "Show clipboard": [text] is the clipboard's text, read only on that tap.
     * Only tokens that are destinations of this network are offered.
     */
    fun onClipboardRead(text: String?) {
        val entries = text.orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && detectSendDestination(it, network) is DestinationParse.Valid }
            .distinct()
            .take(MAX_CLIPBOARD_ENTRIES)
        _uiState.update { it.copy(clipboardShown = true, clipboardEntries = entries) }
    }

    fun onClipboardEntrySelected(entry: String) {
        onAddressChanged(entry)
        _uiState.update { it.copy(clipboardShown = false, clipboardEntries = emptyList()) }
    }

    fun onAddressContinue() {
        if (!_uiState.value.canContinueAddress) return
        val parse = _uiState.value.addressParse
        if (parse is DestinationParse.Valid) {
            enterAmountStep(parse.destination, parse.amount)
        }
    }

    private fun enterAmountStep(destination: SendDestination, amount: Dash?) {
        val previous = _uiState.value.destination
        val sameDestination = previous == destination
        _uiState.update {
            it.copy(
                step = ExternalSendStep.AMOUNT,
                destination = destination,
                destinationIsOwnIdentity = if (sameDestination) it.destinationIsOwnIdentity else null,
                identityResolution = if (sameDestination) it.identityResolution else null,
                sourceChosenByUser = it.sourceChosenByUser && sameDestination,
                amountText = amount?.toKeypadString() ?: if (sameDestination) it.amountText else "0",
                dashMode = if (amount != null) true else it.dashMode,
                // A new destination (or a URI amount) resets the amount, Max and a pending Max with it.
                maxAmount = if (sameDestination && amount == null) it.maxAmount else null,
                maxPending = sameDestination && amount == null && it.maxPending,
                // A URI amount is exact; it stays pinned until the user edits it.
                pinnedAmount = amount ?: if (sameDestination) it.pinnedAmount else null,
                confirm = null,
                showSourceSheet = false
            )
        }
        savedState[KEY_STEP] = ExternalSendStep.AMOUNT.name
        saveAmount()
        if (!sameDestination || _uiState.value.identityResolution == null) {
            checkIdentity(destination)
        }
        if (!autoPickSource()) {
            restartBalance()
        }
    }

    // ── Amount step ──────────────────────────────────────────────────────────

    fun onKeyInput(key: String) {
        val state = _uiState.value
        if (!state.formEditable) return
        // Editing a Max amount edits what is on show.
        val base = if (state.isMax) displayTextFor(state.amount, state.dashMode, state.rate) else state.amountText
        val decimals = if (state.dashMode) DASH_DECIMALS else FIAT_DECIMALS
        val text = processAmountKeyInput(base, key, decimals)
        // A key the keypad rejected (text unchanged) keeps the exact amount; any key cancels a
        // pending Max (the user is entering an amount themselves).
        if (text == state.amountText && !state.isMax && !state.maxPending) return
        _uiState.update { it.copy(amountText = text, maxAmount = null, maxPending = false, pinnedAmount = null) }
        saveAmount()
    }

    fun onCurrencySelected(dashMode: Boolean) {
        val state = _uiState.value
        if (state.dashMode == dashMode) return
        if (!dashMode && state.rate == null) return
        val amount = state.amount
        val text = if (amount.isZero) "0" else displayTextFor(amount, dashMode, state.rate)
        // Switching the currency shown never changes what is sent: the exact amount stays
        // pinned (Max already is) until the user edits it.
        _uiState.update {
            it.copy(
                dashMode = dashMode,
                amountText = text,
                pinnedAmount = if (amount.isZero || state.isMax) null else amount
            )
        }
        saveAmount()
    }

    fun onMaxClick() {
        val state = _uiState.value
        val max = state.maxSendable ?: return
        if (!state.formEditable) return
        _uiState.update {
            it.copy(
                maxAmount = max,
                maxPending = false,
                pinnedAmount = null,
                amountText = displayTextFor(max, it.dashMode, it.rate)
            )
        }
        saveAmount()
    }

    fun onShowSourceSheet() {
        if (_uiState.value.finished) return
        _uiState.update { it.copy(showSourceSheet = true) }
    }

    fun onDismissSourceSheet() {
        _uiState.update { it.copy(showSourceSheet = false) }
    }

    /** Only a source that is listed and can pay the destination can be picked. */
    fun onSourceSelected(source: SendBalance) {
        val state = _uiState.value
        if (state.finished) return
        if (source !in state.sources || state.supportOf(source) != SendSupport.SUPPORTED) return
        selectSource(source, byUser = true)
        _uiState.update { it.copy(showSourceSheet = false) }
    }

    fun onRetryIdentity() {
        val destination = _uiState.value.destination as? SendDestination.Identity ?: return
        _uiState.update { it.copy(identityResolution = null) }
        checkIdentity(destination)
    }

    private fun selectSource(source: SendBalance, byUser: Boolean) {
        val changed = _uiState.value.source != source
        _uiState.update {
            // Max follows the source: the old source's Max is never kept as an amount for the new
            // one. Until the new source's Max is read the amount is zero (Continue stays disabled).
            val followMax = changed && (it.isMax || it.maxPending)
            it.copy(
                source = source,
                sourceChosenByUser = it.sourceChosenByUser || byUser,
                maxAmount = if (changed) null else it.maxAmount,
                maxPending = if (changed) followMax else it.maxPending,
                pinnedAmount = if (followMax) null else it.pinnedAmount,
                amountText = if (followMax) "0" else it.amountText,
                balance = if (changed) null else it.balance,
                maxSendable = if (changed) null else it.maxSendable
            )
        }
        saveAmount()
        savedState[KEY_SOURCE] = source.name
        savedState[KEY_SOURCE_CHOSEN] = _uiState.value.sourceChosenByUser
        if (changed || balanceJob == null) {
            restartBalance()
        }
    }

    /**
     * Until the user picks a source: the first supported one in Core, Shielded, Platform,
     * Identity order. A user's pick that is no longer listed (identity gone) is dropped.
     * Returns whether the balance observation was restarted.
     */
    private fun autoPickSource(): Boolean {
        val state = _uiState.value
        if (state.destination == null) return false
        if (state.sourceChosenByUser && state.source in state.sources) return false
        val pick = state.sources.firstOrNull { state.supportOf(it) == SendSupport.SUPPORTED }
            ?: state.sources.first()
        if (state.sourceChosenByUser) {
            _uiState.update { it.copy(sourceChosenByUser = false) }
        }
        if (pick == state.source && balanceJob != null) return false
        selectSource(pick, byUser = false)
        return true
    }

    /** Observe the selected source's balance and re-read Max on every balance change. */
    private fun restartBalance() {
        balanceJob?.cancel()
        val state = _uiState.value
        val destinationKind = balanceKindOf(state.destination ?: return)
        val source = state.source
        val generation = ++balanceGeneration
        balanceJob = service.observeBalance(source)
            .onEach { balance ->
                if (generation == balanceGeneration) _uiState.update { it.copy(balance = balance) }
            }
            .transformLatest { balance ->
                if (balance == null) {
                    emit(balance to null)
                    return@transformLatest
                }
                var max = service.maxSendable(source, destinationKind)
                emit(balance to max)
                // For a pair whose Max is its bound, an unknown Max blocks Continue, and the
                // balance feed is distinct: an unchanged balance never re-reads it. So a failed
                // read is retried on the balance poll cadence until it is known.
                while (max == null && maxBoundsAmount(source, destinationKind)) {
                    delay(maxRetryDelayMs)
                    max = service.maxSendable(source, destinationKind)
                    if (max != null) emit(balance to max)
                }
            }
            .onEach { (balance, max) ->
                if (generation == balanceGeneration) onMaxSendable(balance, max)
            }
            .launchIn(viewModelScope)
    }

    /**
     * The selected source's [max] was read for its [balance] (null: the balance is not known yet).
     * A pending Max becomes that Max, exactly as [onMaxClick] sets it; a source with a known
     * balance but no positive Max drops the pending Max and leaves the amount at zero.
     */
    private fun onMaxSendable(balance: Dash?, max: Dash?) {
        val before = _uiState.value.maxPending
        _uiState.update {
            when {
                !it.maxPending || balance == null -> it.copy(maxSendable = max)
                max != null && max.isPositive -> it.copy(
                    maxSendable = max,
                    maxAmount = max,
                    maxPending = false,
                    pinnedAmount = null,
                    amountText = displayTextFor(max, it.dashMode, it.rate)
                )
                else -> it.copy(maxSendable = max, maxPending = false, amountText = "0", pinnedAmount = null)
            }
        }
        if (before) saveAmount()
    }

    private fun checkIdentity(destination: SendDestination) {
        identityJob?.cancel()
        ownIdentityJob?.cancel()
        if (destination !is SendDestination.Identity) return
        val id = destination.identityIdBase58
        identityJob = viewModelScope.launch {
            val resolution = service.resolveIdentity(id)
            if (_uiState.value.destination == destination) {
                _uiState.update { it.copy(identityResolution = resolution) }
            }
        }
        ownIdentityJob = viewModelScope.launch {
            val own = service.isOwnIdentity(id)
            if (_uiState.value.destination == destination) {
                _uiState.update { it.copy(destinationIsOwnIdentity = own) }
                autoPickSource()
            }
        }
    }

    // ── Confirm and send ─────────────────────────────────────────────────────

    fun onAmountContinue() {
        val state = _uiState.value
        if (!state.canContinueAmount) return
        val destination = state.destination ?: return
        // The SDK wallet is captured here, without suspending: the send is refused unless the
        // same wallet is still bound when it runs.
        val request = ExternalSendRequest(
            state.source,
            destination,
            state.amount,
            state.isMax,
            service.currentWalletIdOrNull()
        )
        _uiState.update { it.copy(confirm = ExternalSendConfirm(request)) }
        feeJob?.cancel()
        feeJob = viewModelScope.launch {
            val fee = service.estimateFee(request.source, balanceKindOf(request.destination))
            _uiState.update { state ->
                val confirm = state.confirm
                if (confirm?.request == request) {
                    state.copy(confirm = confirm.copy(fee = fee, feeLoading = false))
                } else {
                    state
                }
            }
        }
    }

    fun onDismissConfirm() {
        feeJob?.cancel()
        _uiState.update { it.copy(confirm = null) }
    }

    /**
     * The request the confirm sheet shows, captured BEFORE authentication so the send is
     * exactly what the user confirmed; null when there is nothing to confirm.
     */
    fun confirmedRequest(): ExternalSendRequest? {
        val state = _uiState.value
        return state.confirm?.request?.takeIf { state.canContinueAmount }
    }

    /**
     * Authenticated: send [request]. Refused unless it is still the request on the confirm
     * sheet (the sheet was dismissed or replaced while authenticating).
     */
    fun submit(request: ExternalSendRequest): Boolean {
        if (_uiState.value.confirm?.request != request) return false
        if (!executor.submit(request)) return false
        _uiState.update { it.copy(confirm = null) }
        return true
    }

    /**
     * Whether [txId] is still the sent outcome to open in the transaction result screen. The
     * caller opens it and then calls [onResultAcknowledged] in the same main-thread turn, which
     * clears it, so it is opened once.
     */
    fun takeTransactionResult(txId: String): Boolean = _uiState.value.openTransactionResult == txId

    /**
     * The user closed the outcome. Not sent → back to editing the amount; every other outcome
     * is terminal and the flow ends (the fragment leaves).
     */
    fun onResultAcknowledged() {
        val status = _uiState.value.status as? ExternalSendStatus.Done ?: return
        if (status.result is ExternalSendResult.NotSent) {
            executor.acknowledge()
            // Max is chosen again deliberately; the amount it was stays exact.
            _uiState.update {
                it.copy(
                    maxAmount = null,
                    maxPending = false,
                    pinnedAmount = if (it.isMax) it.amount else it.pinnedAmount
                )
            }
            saveAmount()
        } else {
            // Set before the executor can reach Idle, so the form never lights up in between.
            savedState[KEY_FINISHED] = true
            _uiState.update { it.copy(finished = true, showSourceSheet = false, confirm = null) }
            executor.acknowledge()
        }
    }

    /**
     * Back inside the flow: closes a sheet, or returns from the amount to the address page.
     * Returns false when the screen itself should be left (the send, if any, continues).
     */
    fun onBack(): Boolean {
        val state = _uiState.value
        return when {
            state.status is ExternalSendStatus.Sending -> false
            // The flow is over: leave, never back to an editable address page.
            state.finished -> false
            state.confirm != null -> {
                onDismissConfirm()
                true
            }
            state.showSourceSheet -> {
                onDismissSourceSheet()
                true
            }
            state.step == ExternalSendStep.AMOUNT -> {
                _uiState.update { it.copy(step = ExternalSendStep.ADDRESS) }
                savedState[KEY_STEP] = ExternalSendStep.ADDRESS.name
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
        savedState[KEY_MAX_PENDING] = state.maxPending
        savedState[KEY_PINNED_DUFFS] = state.pinnedAmount?.duffs
    }
}
