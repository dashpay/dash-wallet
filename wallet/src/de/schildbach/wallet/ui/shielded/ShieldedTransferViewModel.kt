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
import de.schildbach.wallet.payments.ChainLockedCoinSelector
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.L1VerificationStatus
import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.common.util.toFiat
import org.slf4j.LoggerFactory
import java.util.Locale
import javax.inject.Inject

/**
 * The blocked-ToShielded toast's LIVE verification status, derived from
 * the L1 shadow-sync harness ([L1ShadowSyncService.verificationStatus] +
 * [L1ShadowSyncService.progress]) by [mapVerificationStatus]. `null`
 * means "no usable live data" and the toast falls back to the static
 * "Verifying your balance — transfers … available shortly" copy.
 */
sealed class ShieldedVerificationStatus {
    /**
     * The shadow chain is still scanning. Block counts are pre-formatted
     * with digit grouping (host-JVM-testable; the composable only fills
     * the string template).
     */
    data class Scanning(
        val scannedBlocks: String,
        val targetBlocks: String,
        val percent: Int
    ) : ShieldedVerificationStatus()

    /** Chain synced; balance parity not confirmed yet (probe pending / non-terminal mismatch). */
    object AlmostDone : ShieldedVerificationStatus()

    /** The harness stood down (terminal) — tell the tester to export logs. */
    object Failed : ShieldedVerificationStatus()
}

/**
 * Map the harness state to the toast's [ShieldedVerificationStatus] —
 * pure, host-JVM-testable. Scanning picks the single most meaningful
 * block pair: the header counts during the header phase, the
 * wallet-relevant filter-scan counts from then on (falling back to the
 * header pair while the filter block has no target yet); no usable
 * target → `null` (static fallback copy). VERIFIED also maps to `null`:
 * the funding gate opens on the next check and the toast disappears.
 */
internal fun mapVerificationStatus(
    status: L1VerificationStatus,
    progress: ShadowSyncProgress,
    locale: Locale = Locale.getDefault()
): ShieldedVerificationStatus? = when (status) {
    L1VerificationStatus.FAILED -> ShieldedVerificationStatus.Failed
    L1VerificationStatus.PROBING -> ShieldedVerificationStatus.AlmostDone
    L1VerificationStatus.SCANNING -> {
        val useFilters = progress.phase != ShadowSyncPhase.HEADERS && progress.filterTarget > 0
        val (height, target) = if (useFilters) {
            progress.filterHeight to progress.filterTarget
        } else {
            progress.headerHeight to progress.headerTarget
        }
        if (target > 0) {
            ShieldedVerificationStatus.Scanning(
                scannedBlocks = String.format(locale, "%,d", height.coerceIn(0, target)),
                targetBlocks = String.format(locale, "%,d", target),
                percent = progress.overallPercent.toInt().coerceIn(0, 100)
            )
        } else {
            null
        }
    }
    L1VerificationStatus.UNKNOWN, L1VerificationStatus.VERIFIED -> null
}

/**
 * Why the transfer screen's blocked-state toast is showing — the pure,
 * host-JVM-testable priority mapping behind
 * [ShieldedTransferUIState.blockedReason]; the composable only picks the
 * string. Ordered by which problem the user must wait out first:
 *
 * 1. [CHAIN_SYNCING] — the runtime is not up or the dashj L1 chain is
 *    still syncing; both directions are blocked.
 * 2. [POOL_SYNCING] — the shielded pool is still catching up
 *    ([ShieldedSyncStatus] != READY); both directions are blocked (see
 *    [ShieldedTransferUIState.shieldedPoolReady]).
 * 3. [FUNDING_PENDING] — everything is synced but the Dash Wallet →
 *    Shielded L1 funding-evidence gate is closed (shadow-SPV parity
 *    pending); only that direction is blocked, and the toast may show the
 *    live [ShieldedVerificationStatus].
 */
enum class ShieldedBlockedReason { CHAIN_SYNCING, POOL_SYNCING, FUNDING_PENDING }

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
    /**
     * ChainLocked-only L1 balance (via [ChainLockedCoinSelector]) — the
     * "From: Dash Wallet" display AND the transferable/Max limit. Funds
     * a reorg could still take back are never offered for shielding.
     */
    val walletBalance: Dash = Dash.ZERO,
    /**
     * The wallet's TOTAL (estimated) L1 balance — only used to derive
     * [pendingWalletBalance]; never transferable.
     */
    val totalWalletBalance: Dash = Dash.ZERO,
    val shieldedBalance: Dash = Dash.ZERO,
    /** True once the shielded runtime bring-up succeeded. */
    val ready: Boolean = false,
    /**
     * Live [ShieldedSyncStatus] of the shielded pool, mirrored from
     * [ShieldedBalanceService.shieldedSyncStatus]. Conservative
     * [ShieldedSyncStatus.NOT_READY] default until the first emission;
     * gates BOTH directions via [shieldedPoolReady].
     */
    val shieldedSyncStatus: ShieldedSyncStatus = ShieldedSyncStatus.NOT_READY,
    val readyCheckDone: Boolean = false,
    /**
     * True while the dashj L1 chain is synced ([isChainSyncedForTransfer]:
     * the canonical [org.dash.wallet.common.data.entity.BlockchainState
     * .isSynced], OR an idle-synced wallet whose chain tip is current but
     * whose persisted sync percentage was reset by the app restart).
     * Both transfer directions are blocked until then — conservative
     * `false` default until the first state emission.
     */
    val chainSynced: Boolean = false,
    /**
     * True while the "Transfers take different times" sheet is open. Auto
     * set once on the user's first visit ([DashPayConfig
     * .SHIELDED_TIMING_INFO_SHOWN]); the info icon re-opens it manually.
     */
    val showTimingInfo: Boolean = false,
    /**
     * True when the Dash Wallet → Shielded direction can fund from the
     * L1 balance (the shadow-SPV parity funding gate). Gates [canContinue]
     * for that direction. Fed LIVE from
     * [ShieldedBalanceService.observeWalletShieldingAvailable] (not the
     * one-shot [ShieldedBalanceService.isWalletShieldingAvailable]
     * snapshot), so the screen re-renders the instant the gate opens.
     */
    val walletShieldingAvailable: Boolean = false,
    /**
     * Live status for the funding-gate toast (the [walletShieldingAvailable]
     * == false while [chainSynced] case) — see [ShieldedVerificationStatus].
     * `null` (flags off / no shadow data) keeps the static fallback copy.
     */
    val verificationStatus: ShieldedVerificationStatus? = null,
    val showConfirm: Boolean = false,
    /**
     * Mirrored LIVE from [ShieldedTransferExecutor.submitState] — the
     * spend runs on the application scope so it survives this screen,
     * and a recreated ViewModel re-attaches to an in-flight Proving or
     * an unacknowledged terminal result. Never written directly.
     */
    val submitState: ShieldedSubmitState = ShieldedSubmitState.Idle,
    /**
     * True once the user dismissed the proving dialog for the current
     * in-flight operation: the spend keeps running on the app scope and
     * the screen shows the inline in-progress state (the Continue button
     * becomes "Sending your transfer…") instead of the modal. Auto-reset
     * whenever [submitState] leaves Proving.
     */
    val provingDismissed: Boolean = false
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

    /**
     * The not-yet-ChainLocked part of the wallet's L1 balance
     * (total − chainlocked, clamped at zero — the selector universes can
     * transiently disagree while flows race). Shown as "<amount> pending"
     * under the From "Dash Wallet" row so a freshly funded user
     * understands why the transferable number is smaller: waiting for the
     * chainlock resolves it.
     */
    val pendingWalletBalance: Dash
        get() = Dash((totalWalletBalance.duffs - walletBalance.duffs).coerceAtLeast(0))

    val insufficientFunds: Boolean
        get() = amount.isGreaterThan(availableBalance)

    /**
     * "You will transfer ~" hint, denominated in what ARRIVES (Figma
     * 1746:18462 / 1746:18478): Dash Wallet → Shielded lands as Platform
     * credits ("~ 100,000,000,000 C" for 1 DASH — gross 1 duff = 1000
     * credits conversion, per the design); Shielded → Dash Wallet lands
     * as Dash ("~ 1.00 Đ").
     */
    val transferHintText: String
        get() = when (direction) {
            ShieldedTransferDirection.ToShielded -> "~ ${amount.toCreditsString()}"
            ShieldedTransferDirection.FromShielded -> "~ ${amount.toDisplayString()}"
        }

    /** True when [transferHintText] is credits-denominated (trailing "C" symbol). */
    val transferHintIsCredits: Boolean
        get() = direction == ShieldedTransferDirection.ToShielded

    /** The L1-funding gate only applies to the Dash Wallet → Shielded direction. */
    val directionAvailable: Boolean
        get() = direction == ShieldedTransferDirection.FromShielded || walletShieldingAvailable

    /**
     * True once the shielded pool has caught up ([ShieldedSyncStatus.READY]).
     * Gates BOTH directions:
     *
     * - Dash Wallet → Shielded: both live Ambiguous shield failures happened
     *   while the pool was still SYNCING after app start — the Type 18 shield
     *   transition needs the pool's anchor/note bookkeeping caught up, and an
     *   Ambiguous result is terminal for the user ("may have gone through, do
     *   not retry"), so it must not be risked on a known-stale pool.
     * - Shielded → Dash Wallet: spending notes requires an up-to-date note
     *   set + recorded anchors — a mid-sync view can build the ~30s proof
     *   against already-spent notes or a stale anchor and fail (or land
     *   Ambiguous) after the wait. The available shielded balance itself is
     *   also untrustworthy until READY (it reads 0 during a re-scan).
     */
    val shieldedPoolReady: Boolean
        get() = shieldedSyncStatus == ShieldedSyncStatus.READY

    /**
     * Why the blocked-state toast is showing, or null when no gate blocks
     * (or the initial readiness check has not finished — no toast flash
     * before the first result). See [ShieldedBlockedReason] for the
     * priority order.
     */
    val blockedReason: ShieldedBlockedReason?
        get() = when {
            !readyCheckDone -> null
            !ready || !chainSynced -> ShieldedBlockedReason.CHAIN_SYNCING
            !shieldedPoolReady -> ShieldedBlockedReason.POOL_SYNCING
            !directionAvailable -> ShieldedBlockedReason.FUNDING_PENDING
            else -> null
        }

    /**
     * True while a transfer operation is in flight ([ShieldedSubmitState
     * .Proving]) — regardless of whether the proving dialog is up or was
     * dismissed. The Continue button shows the in-progress state and a
     * second submit is impossible ([canContinue] excludes Proving, and
     * [ShieldedTransferExecutor.submit] refuses atomically anyway).
     */
    val transferInFlight: Boolean
        get() = submitState == ShieldedSubmitState.Proving

    /**
     * True while the blocked-state status toast must be hidden: a modal
     * overlay is up (confirm/timing sheet, proving dialog, terminal
     * result overlays) or a submit is in flight. A live-updating status
     * line behind the dimmed modal reads as glitchy (live user feedback),
     * and the modals/in-progress button carry their own messaging.
     */
    val blockedToastSuppressed: Boolean
        get() = showConfirm || showTimingInfo ||
            (submitState != ShieldedSubmitState.Idle && submitState !is ShieldedSubmitState.NotSent)

    val canContinue: Boolean
        get() = ready && chainSynced && shieldedPoolReady && directionAvailable &&
            amount.isPositive && !insufficientFunds &&
            (dashMode || rate != null) &&
            // NotSent is provably pre-broadcast, so retrying is safe
            (submitState == ShieldedSubmitState.Idle || submitState is ShieldedSubmitState.NotSent)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShieldedTransferViewModel @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    walletDataProvider: WalletDataProvider,
    private val dashPayConfig: DashPayConfig,
    blockchainStateProvider: BlockchainStateProvider,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesProvider,
    l1ShadowSyncService: L1ShadowSyncService,
    private val transferExecutor: ShieldedTransferExecutor
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(ShieldedTransferViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(ShieldedTransferUIState())
    val uiState: StateFlow<ShieldedTransferUIState> = _uiState.asStateFlow()

    init {
        // The spend itself lives in the app-scoped ShieldedTransferExecutor
        // so it survives this ViewModel/screen: this mirror re-attaches a
        // recreated screen to an in-flight op (the proving dialog / inline
        // in-progress button shows again instead of a blank form) or to an
        // unacknowledged terminal result. Observation only — re-attaching
        // can NEVER re-submit: submission happens exclusively through
        // onConfirm (a user gesture) and the executor atomically refuses
        // while an operation is in flight or a terminal no-retry state is
        // held. The proving-dialog dismissal is per-operation: it resets
        // whenever the state leaves Proving.
        transferExecutor.submitState
            .onEach { submitState ->
                _uiState.value = _uiState.value.copy(
                    submitState = submitState,
                    provingDismissed = if (submitState == ShieldedSubmitState.Proving) {
                        _uiState.value.provingDismissed
                    } else {
                        false
                    }
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val ready = shieldedBalanceService.ensureShieldedReady()
            _uiState.value = _uiState.value.copy(
                ready = ready,
                readyCheckDone = true
            )
        }

        // L1 funding-evidence gate, LIVE: isWalletShieldingAvailable() is a
        // one-shot snapshot, so reading it once at init left the
        // "Verifying your balance…" toast (blockedReason FUNDING_PENDING)
        // stuck for minutes after the shadow harness had actually reached
        // parity MATCH — the gate only re-opened on screen re-entry. This
        // flow re-derives the gate on every parity probe (~60s) so the
        // screen unblocks by itself. Flag-gated upstream: with the shadow
        // harness off it stays false (inert).
        shieldedBalanceService.observeWalletShieldingAvailable()
            .onEach { _uiState.value = _uiState.value.copy(walletShieldingAvailable = it) }
            .launchIn(viewModelScope)

        // First visit: auto-open the "Transfers take different times"
        // sheet once; dismissal latches the flag (onTimingInfoDismissed).
        viewModelScope.launch {
            val shown = try {
                dashPayConfig.get(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN) == true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("failed to read the timing-info flag; not auto-showing the sheet", e)
                true
            }
            if (!shown) {
                _uiState.value = _uiState.value.copy(showTimingInfo = true)
            }
        }

        // L1 sync gate: both directions stay blocked until the chain is
        // synced. isChainSyncedForTransfer, not the raw isSynced(): on
        // every app start WalletApplication.resetBlockchainSyncProgress()
        // zeroes percentageSync and it only returns to 100 once the
        // service's download tracker fires on a fresh peer connection —
        // an idle-synced wallet (current tip, nothing to download) must
        // not be locked out in the meantime.
        blockchainStateProvider.observeState()
            .onEach { state ->
                _uiState.value = _uiState.value.copy(
                    chainSynced = state.isChainSyncedForTransfer()
                )
            }
            .launchIn(viewModelScope)

        shieldedBalanceService.observeShieldedBalance()
            .onEach { _uiState.value = _uiState.value.copy(shieldedBalance = it) }
            .launchIn(viewModelScope)

        // Shielded-pool readiness gate: both directions stay blocked until
        // the pool's first sync pass has finished (READY) — see the
        // shieldedPoolReady KDoc for why each direction needs it. Live: the
        // gate opens by itself once the pass lands, no screen re-entry.
        shieldedBalanceService.shieldedSyncStatus
            .onEach { _uiState.value = _uiState.value.copy(shieldedSyncStatus = it) }
            .launchIn(viewModelScope)

        // ChainLocked-only wallet balance: re-select whenever the
        // chainlock/best heights move (Room state) or the wallet changes
        // (the observeBalance listener) — see ChainLockedCoinSelector for
        // the fallback decision when no chainlock height is known yet.
        blockchainStateProvider.observeState()
            .map { state -> (state?.chainlockHeight ?: 0) to (state?.bestChainHeight ?: 0) }
            .distinctUntilChanged()
            .flatMapLatest { (chainLockHeight, bestChainHeight) ->
                walletDataProvider.observeBalance(
                    coinSelector = ChainLockedCoinSelector(chainLockHeight, bestChainHeight)
                )
            }
            .onEach { _uiState.value = _uiState.value.copy(walletBalance = Dash(it.value)) }
            .launchIn(viewModelScope)

        // Total balance only feeds the "<amount> pending" explainer line.
        walletDataProvider.observeTotalBalance()
            .onEach { _uiState.value = _uiState.value.copy(totalWalletBalance = Dash(it.value)) }
            .launchIn(viewModelScope)

        // Live verification status for the funding-gate toast. Flag-gated
        // upstream: with the shadow harness off, verificationStatus stays
        // UNKNOWN and this maps to null — the static fallback copy.
        combine(
            l1ShadowSyncService.verificationStatus,
            l1ShadowSyncService.progress
        ) { status, progress -> mapVerificationStatus(status, progress) }
            .distinctUntilChanged()
            .onEach { _uiState.value = _uiState.value.copy(verificationStatus = it) }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { code -> _uiState.value = _uiState.value.copy(fiatCode = code) }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { rate -> _uiState.value = _uiState.value.copy(rate = rate?.fiat) }
            .launchIn(viewModelScope)
    }

    /**
     * Clears per-visit state; called when the screen is (re)entered.
     * The executor decides what a fresh visit may drop: an in-flight
     * Proving and the funds-critical terminal states survive (the mirror
     * re-attaches to them), only stale Success/NotSent leftovers reset.
     */
    fun reset(direction: ShieldedTransferDirection = ShieldedTransferDirection.ToShielded) {
        transferExecutor.clearForNewVisit()
        _uiState.value = _uiState.value.copy(
            direction = direction,
            amountText = "0",
            dashMode = true,
            showConfirm = false
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
            amountText = processAmountKeyInput(state.amountText, key, maxDecimals)
        )
        // typing again clears an inline "not sent" error
        transferExecutor.clearRetryableResult()
    }

    fun onMaxClick() {
        val state = _uiState.value
        val max = state.availableBalance
        val text = if (state.dashMode) {
            max.toKeypadString()
        } else {
            max.toFiatAt(state.rate)?.toPlainString() ?: return
        }
        _uiState.value = state.copy(amountText = text)
        transferExecutor.clearRetryableResult()
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
        _uiState.value = state.copy(direction = direction)
        transferExecutor.clearRetryableResult()
    }

    fun onContinue() {
        if (_uiState.value.canContinue) {
            _uiState.value = _uiState.value.copy(showConfirm = true)
        }
    }

    fun onDismissConfirm() {
        _uiState.value = _uiState.value.copy(showConfirm = false)
    }

    /** Manual re-open of the timing sheet (nav-bar info icon). */
    fun onShowTimingInfo() {
        _uiState.value = _uiState.value.copy(showTimingInfo = true)
    }

    /**
     * Timing sheet dismissed — latch [DashPayConfig.SHIELDED_TIMING_INFO_SHOWN]
     * so the auto-show never happens again (idempotent on manual re-opens).
     */
    fun onTimingInfoDismissed() {
        _uiState.value = _uiState.value.copy(showTimingInfo = false)
        viewModelScope.launch {
            try {
                dashPayConfig.set(DashPayConfig.SHIELDED_TIMING_INFO_SHOWN, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("failed to persist the timing-info flag", e)
            }
        }
    }

    /**
     * Hands the spend to the app-scoped [ShieldedTransferExecutor] so it
     * survives this screen: "Dash Wallet → Shielded" is the L1 asset-lock
     * pipeline ([ShieldedBalanceService.shieldFromWallet]); "Shielded →
     * Dash Wallet" is the Type 19 withdraw. The executor maps the
     * SdkWriteResult contract onto [ShieldedSubmitState] (mirrored here)
     * and announces terminal outcomes to a user who left the screen —
     * see the executor KDoc. Single-attempt semantics unchanged: the
     * executor atomically refuses unless it is Idle/NotSent.
     */
    fun onConfirm() {
        val state = _uiState.value
        if (!state.canContinue || !state.showConfirm) return
        _uiState.value = state.copy(showConfirm = false)
        if (!transferExecutor.submit(state.direction, state.amount)) {
            log.warn("shielded transfer submit refused — an operation is already in flight")
        }
    }

    /**
     * The proving dialog's dismiss action: hides the modal only — the
     * spend keeps running on the app scope and the screen shows the
     * inline in-progress state until the operation finishes.
     */
    fun onDismissProving() {
        if (_uiState.value.submitState == ShieldedSubmitState.Proving) {
            _uiState.value = _uiState.value.copy(provingDismissed = true)
        }
    }

    /** Dismisses a terminal result overlay (Success / MayHaveGoneThrough). */
    fun onResultHandled() {
        transferExecutor.acknowledge()
        _uiState.value = _uiState.value.copy(amountText = "0")
    }
}

/**
 * The chain-tip freshness window that counts as "synced" when the recorded
 * sync percentage is stale — the same 1-hour rule BlockchainServiceImpl
 * applies when deciding whether to show its "syncing" notification
 * (blocks target ~2.5 min, so a current tip is always well inside it).
 */
internal const val CHAIN_TIP_FRESHNESS_MS = 60L * 60 * 1000
/**
 * The transfer screens' L1 sync gate.
 *
 * [BlockchainState.isSynced] alone (`!replaying && percentageSync == 100 &&
 * !syncFailed()`) is NOT reliable for an idle-synced wallet:
 * `WalletApplication.onCreate` calls `resetBlockchainSyncProgress()` on every
 * app start, zeroing the persisted `percentageSync`, and only the blockchain
 * service's download tracker (`progress`/`doneDownload`, which need a fresh
 * peer connection to fire) ever sets it back to 100. Until that happens a
 * fully synced wallet sits at `percentageSync == 0` and `isSynced()` returns
 * false. So a state whose chain tip is current (best-chain date within
 * [CHAIN_TIP_FRESHNESS_MS], not replaying, no network impediment) also
 * passes — mirroring the freshness rule the service itself uses for its
 * sync notification. `null` (no persisted state yet) stays blocked.
 */
internal fun BlockchainState?.isChainSyncedForTransfer(
    nowMillis: Long = System.currentTimeMillis()
): Boolean {
    if (this == null) return false
    if (isSynced()) return true
    val tipTime = bestChainDate?.time ?: return false
    return !replaying && !syncFailed() && nowMillis - tipTime < CHAIN_TIP_FRESHNESS_MS
}
