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
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShieldFromWalletOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val canContinue: Boolean
        get() = ready && chainSynced && directionAvailable && amount.isPositive && !insufficientFunds &&
            (dashMode || rate != null) &&
            // NotSent is provably pre-broadcast, so retrying is safe
            (submitState == ShieldedSubmitState.Idle || submitState is ShieldedSubmitState.NotSent)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShieldedTransferViewModel @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    private val walletDataProvider: WalletDataProvider,
    private val dashPayConfig: DashPayConfig,
    blockchainStateProvider: BlockchainStateProvider,
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
