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

package org.dash.wallet.integrations.maya.ui

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * UI state for the DashDEX buy "Enter amount" screen (Figma node 35200-34693).
 *
 * The amount the user types is held as a raw string ([amount]) so it round-trips through
 * [processAmountKeyInput] exactly as displayed (e.g. a trailing decimal point) for the
 * currently-selected currency. The equivalent values in the other two display currencies are
 * tracked in an [Amount] (see [DEXEnterAmountViewModel]); switching currency re-derives [amount]
 * from the converted value. [continueEnabled] is derived from whether the value is greater than
 * zero.
 */
data class DEXEnterAmountUIState(
    // The asset being bought (e.g. "BTC.BTC") and its display code (e.g. "BTC"), passed in
    // from the currency picker. The code is offered as one of the alternate display currencies
    // in the EnterAmount picker alongside the user's fiat and DASH.
    val asset: String = "",
    val assetCurrencyCode: String = "",
    // Heading form of the asset per design: tokens qualified with their host network
    // ("USDT (Ethereum)"); native L1 coins just the code ("BTC").
    val assetDisplayCode: String = "",
    // Fiat ISO code the amount is entered in (primary input), e.g. "USD".
    val fiatCurrencyCode: String = "USD",
    // Display order for the EnterAmount currency picker: fiat (primary), DASH, asset.
    val currencyCodes: List<String> = listOf("USD", DASH_CURRENCY_CODE),
    val selectedCurrencyIndex: Int = 0,
    // Raw entered amount string, as shown in the primary amount slot, in the selected currency.
    val amount: String = "0",
    val continueEnabled: Boolean = false,
    // True while a buy quote is in flight checking that the entered amount is routable.
    val isValidating: Boolean = false,
    // False when the device has no network connection; the screen shows a no-connection toast.
    val isOnline: Boolean = true,
    // Non-null when the entered amount was rejected by the validation quote: the localized message
    // to show under the amount bar, resolved with [assetCurrencyCode] as its format argument. The
    // provider's raw message is logged, never shown — see [DEXEnterAmountViewModel.onContinueClicked]
    // for which rejections name a reason and which fall back to the neutral catch-all.
    @StringRes val validationErrorRes: Int? = null
)

@HiltViewModel
class DEXEnterAmountViewModel @Inject constructor(
    private val swapProvider: SwapProvider,
    networkState: NetworkStateInt,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(DEXEnterAmountUIState())
    val uiState: StateFlow<DEXEnterAmountUIState> = _uiState.asStateFlow()

    // Tracks the entered value across fiat / DASH / the bought asset at once. Typing anchors the
    // currently-selected currency; the other two are recomputed from the exchange rates so that
    // switching currency shows the converted amount. Persisted to [savedStateHandle] on every change
    // so a typed amount survives process death (Amount is @Parcelize, rates + anchor included).
    private var amount = Amount()

    // In-flight buy-quote validation triggered by Continue (see [onContinueClicked]).
    private var validationJob: Job? = null

    // Fired once the entered amount has passed (or skipped) validation; the Fragment observes this
    // to navigate to the refund-address step.
    val onValidationPassed = SingleLiveEvent<Unit>()

    init {
        // Restore a previously-entered amount after process death. The persisted [Amount] is fully
        // self-contained (values + rates + anchor + codes), so restore it here — not only in
        // setArguments — so downstream steps (refund / receive) can read enteredAmount() even when
        // the OS relaunches straight onto their screen and this screen's setArguments never runs.
        savedStateHandle.get<Amount>(KEY_AMOUNT)?.let { amount = it }

        // Mirror connectivity into the UI state so the screen can show the no-connection toast,
        // matching the coin picker (see MayaViewModel).
        networkState.isConnected
            .onEach { online -> _uiState.update { it.copy(isOnline = online) } }
            .launchIn(viewModelScope)
    }

    /** Persist the entered amount + its asset so it can be restored after process death. */
    private fun persistAmount() {
        savedStateHandle[KEY_AMOUNT] = amount.copy()
        savedStateHandle[KEY_ASSET] = _uiState.value.asset
    }

    /**
     * Seed the screen with the asset/currency selected on the previous (picker) step and the
     * exchange rates needed to convert between the three display currencies. Rates are the fiat
     * price of one DASH and of one unit of the bought asset (both in [fiatCurrencyCode]).
     */
    fun setArguments(
        asset: String,
        assetCurrencyCode: String,
        fiatCurrencyCode: String,
        dashPriceFiat: BigDecimal,
        assetPriceFiat: BigDecimal
    ) {
        // This ViewModel is nav-graph scoped and shared with the refund + receive steps, and this
        // is called from the Fragment's onCreateView — which runs again whenever the user navigates
        // BACK to this screen. Re-seeding then would reset [amount] to zero and wipe an amount the
        // user already committed, so the receive step would send a zero sell amount to SwapKit
        // (surfacing as an opaque `validation_error`). While the ViewModel is alive keep the
        // committed amount for the same asset; a genuinely new entry (or process-death restore) is
        // handled below.
        if (_uiState.value.asset == asset && amount.crypto.signum() > 0) {
            return
        }

        val codes = buildCurrencyCodes(fiatCurrencyCode, assetCurrencyCode)
        // Heading form of the asset: tokens are qualified with their host network
        // ("USDT (Ethereum)"); native L1 coins (BTC.BTC, …) show just the code.
        val network = MayaCurrencyList.networkName(asset)
        val displayCode = if (network != null) "$assetCurrencyCode ($network)" else assetCurrencyCode
        amount = Amount(
            dashCode = DASH_CURRENCY_CODE,
            fiatCode = fiatCurrencyCode,
            cryptoCode = assetCurrencyCode
        ).apply {
            // Guard against missing pool data (0): a zero rate would divide-by-zero in Amount.
            // High scale so Amount's divisions (which inherit the dividend's scale) keep precision.
            dashFiatExchangeRate = (dashPriceFiat.takeIf { it.signum() > 0 } ?: BigDecimal.ONE)
                .setScale(CALC_SCALE, RoundingMode.HALF_UP)
            cryptoFiatExchangeRate = (assetPriceFiat.takeIf { it.signum() > 0 } ?: BigDecimal.ONE)
                .setScale(CALC_SCALE, RoundingMode.HALF_UP)
            anchoredType = CurrencyInputType.Fiat
        }

        // Restore a previously-entered amount after process death (the VM was recreated, so the
        // in-memory [amount] was lost). Only restore when it belongs to this asset, and re-anchor it
        // on the freshly-seeded rates so the converted currencies reflect current prices.
        val restored = savedStateHandle.get<Amount>(KEY_AMOUNT)
            ?.takeIf { savedStateHandle.get<String>(KEY_ASSET) == asset && it.anchoredValue.signum() > 0 }
        if (restored != null) {
            when (restored.anchoredType) {
                CurrencyInputType.Dash -> amount.dash = restored.dash
                CurrencyInputType.Fiat -> amount.fiat = restored.fiat
                CurrencyInputType.Crypto -> amount.crypto = restored.crypto
            }
        }

        val selectedIndex = indexForType(codes, amount.anchoredType, assetCurrencyCode)
        val anchoredValue = amount.getValue(amount.anchoredType)
        val displayString = if (restored != null) {
            // Passes the new asset explicitly: the UI state still carries the previous one here.
            formatForDisplay(anchoredValue, amount.anchoredType, asset)
        } else {
            "0"
        }

        validationJob?.cancel()
        _uiState.update {
            it.copy(
                asset = asset,
                assetCurrencyCode = assetCurrencyCode,
                assetDisplayCode = displayCode,
                fiatCurrencyCode = fiatCurrencyCode,
                currencyCodes = codes,
                selectedCurrencyIndex = selectedIndex,
                amount = displayString,
                continueEnabled = anchoredValue.signum() > 0,
                isValidating = false,
                validationErrorRes = null
            )
        }
        persistAmount()
    }

    /** Picker index for the currently-anchored currency (fiat is always the primary slot, index 0). */
    private fun indexForType(codes: List<String>, type: CurrencyInputType, assetCode: String): Int =
        when (type) {
            CurrencyInputType.Fiat -> 0
            CurrencyInputType.Dash -> codes.indexOf(DASH_CURRENCY_CODE).coerceAtLeast(0)
            CurrencyInputType.Crypto -> codes.indexOf(assetCode).let { if (it < 0) 0 else it }
        }

    /**
     * The amount the user has committed on this (shared, nav-graph-scoped) screen, in all three
     * display currencies (fiat / DASH / bought asset) with their codes and exchange rates. Returns
     * a defensive copy so callers on later steps (e.g. the refund-address screen) can read the
     * amount without mutating the live tracked value.
     */
    fun enteredAmount(): Amount = amount.copy()

    /**
     * Forget the committed amount and its persisted copy. Called when the user leaves the buy flow
     * backwards — back past the enter-amount screen to the coin picker, or "Back home" on the
     * receive screen — so the next entry into the flow starts from a blank amount instead of
     * restoring this one (the ViewModel is nav-graph scoped and outlives those screens while the
     * picker is still on the back stack).
     */
    fun clearEnteredAmount() {
        validationJob?.cancel()
        amount = Amount()
        savedStateHandle.remove<Amount>(KEY_AMOUNT)
        savedStateHandle.remove<String>(KEY_ASSET)
        _uiState.update {
            it.copy(amount = "0", continueEnabled = false, isValidating = false, validationErrorRes = null)
        }
    }

    /** Handle a numeric-keyboard key ("0"–"9", ".", "back", "back_long"). */
    fun onKeyInput(key: String) {
        // Amount entry is disabled while offline — a swap can't be quoted without a connection —
        // and while a validation quote is in flight, so its Success can never navigate forward
        // with an amount other than the one that was validated. Input re-enables when validation
        // fails (on success the screen navigates away).
        // Snapshot-then-update: [amount] is mutated outside the update lambda, which must stay
        // free of side effects because it can re-run on CAS contention. Safe because this
        // ViewModel's state is only written from the main thread.
        val state = _uiState.value
        val type = currencyTypeFor(state, state.selectedCurrencyIndex)
        val updated = processAmountKeyInput(state.amount, key, maxDecimalsFor(type))
        amount.setAnchored(type, updated.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        // Reject input that would push the DASH-equivalent past the protocol maximum, mirroring
        // the common EnterAmountFragment which rejects amounts greater than Constants.MAX_MONEY.
        // Uses the Amount model's own conversion so it caps fiat / DASH / asset entry alike.
        if (amount.dash > maxMoneyDash()) {
            amount.setAnchored(type, state.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            return
        }
        // Editing the amount clears any stale rejection from a previous validation attempt.
        _uiState.update {
            it.copy(
                amount = updated,
                continueEnabled = isPositive(updated),
                validationErrorRes = null
            )
        }
        // Persist so the typed amount survives process death.
        persistAmount()
    }

    /** Switch the active display currency, re-deriving the shown amount from the tracked value. */
    fun onCurrencySelected(index: Int) {
        // Same guards as onKeyInput: no changes while offline or mid-validation.
        // Snapshot-then-update for the same reason as onKeyInput: keep the update lambda pure.
        val state = _uiState.value
        val newIndex = index.coerceIn(0, state.currencyCodes.lastIndex.coerceAtLeast(0))
        val type = currencyTypeFor(state, newIndex)
        val value = amount.getValue(type)
        amount.anchoredType = type
        _uiState.update {
            it.copy(
                selectedCurrencyIndex = newIndex,
                amount = formatForDisplay(value, type),
                continueEnabled = value.signum() > 0
            )
        }
        // Persist so the active currency (anchor) survives process death.
        persistAmount()
    }

    /**
     * Validate the entered amount when the user presses Continue, then signal navigation via
     * [onValidationPassed]. The check is a quote-only buy ([SwapProvider.validateBuyOrder]) for the
     * crypto-unit sell amount, using a session-generated example address
     * ([MayaCryptoCurrency.getNewExampleAddress]) as the placeholder refund/source address — the
     * user hasn't supplied a real one yet, and a fresh address avoids submitting the well-known
     * hardcoded example to SwapKit. On failure the rejection reason is surfaced inline and
     * navigation is suppressed. An unknown asset is allowed through (we can't validate it here).
     */
    fun onContinueClicked() {
        if (_uiState.value.isValidating || !_uiState.value.isOnline) return

        val sellCrypto = amount.crypto
        if (sellCrypto.signum() <= 0) return

        val asset = _uiState.value.asset
        val exampleAddress = MayaCurrencyList[asset]?.getNewExampleAddress()
        if (asset.isBlank() || exampleAddress.isNullOrBlank()) {
            log.warn("onContinueClicked: no example address for asset={}, skipping validation", asset)
            onValidationPassed.call()
            return
        }

        // Quantize DOWN to the asset's on-chain decimals — the identical form the refund step
        // registers with the order (see DEXRefundAddressFragment.onContinue) — so the amount
        // validated here is the amount actually quoted, and it is exactly representable on chain.
        val sellAmount = MayaCurrencyList[asset]?.formatSwapAmount(sellCrypto)
            ?: sellCrypto.setScale(MAX_CRYPTO_DECIMALS, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString()

        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, validationErrorRes = null, continueEnabled = false) }
            when (val result = swapProvider.validateBuyOrder(asset, sellAmount, exampleAddress)) {
                is ResponseResource.Success -> {
                    _uiState.update {
                        it.copy(
                            isValidating = false,
                            validationErrorRes = null,
                            continueEnabled = isPositive(it.amount)
                        )
                    }
                    onValidationPassed.call()
                }
                is ResponseResource.Failure -> {
                    val error = result.throwable.message
                    log.info(
                        "onContinueClicked: amount {} {} rejected: {}",
                        sellAmount,
                        asset,
                        error
                    )
                    // A below-minimum rejection is the one reason we can name: the provider says so
                    // explicitly (SwapKit's per-provider `…AmountTooSmall`), so show the backend's
                    // own below-minimum copy — the user's fix is to raise the amount. Every other
                    // code stays neutral: they either can't distinguish too-low from
                    // temporarily-unroutable, or they describe the placeholder refund address used
                    // here rather than anything the user entered.
                    val messageRes = if (swapProvider.isAmountTooLowError(error)) {
                        swapProvider.errorMessageRes(error)
                    } else {
                        R.string.dex_enter_amount_invalid
                    }
                    _uiState.update {
                        it.copy(
                            isValidating = false,
                            validationErrorRes = messageRes,
                            continueEnabled = isPositive(it.amount)
                        )
                    }
                }
            }
        }
    }

    private fun Amount.setAnchored(type: CurrencyInputType, value: BigDecimal) {
        // Set at a high scale so the conversions in Amount.update() (BigDecimal division inherits
        // the dividend's scale) don't round a small result like 1 USD -> ~0.028 DASH down to 0.
        val scaled = value.setScale(CALC_SCALE, RoundingMode.HALF_UP)
        when (type) {
            CurrencyInputType.Dash -> dash = scaled
            CurrencyInputType.Fiat -> fiat = scaled
            CurrencyInputType.Crypto -> crypto = scaled
        }
    }

    private fun currencyTypeFor(state: DEXEnterAmountUIState, index: Int): CurrencyInputType =
        when (state.currencyCodes.getOrNull(index)) {
            DASH_CURRENCY_CODE -> CurrencyInputType.Dash
            state.assetCurrencyCode -> CurrencyInputType.Crypto
            else -> CurrencyInputType.Fiat
        }

    private fun maxDecimalsFor(type: CurrencyInputType, asset: String = _uiState.value.asset): Int = when (type) {
        CurrencyInputType.Fiat -> MAX_FIAT_DECIMALS
        CurrencyInputType.Dash -> MAX_CRYPTO_DECIMALS
        // Cap crypto entry at the asset's on-chain decimals (e.g. 6 for USDC) so a typed
        // amount is always exactly representable — it goes into the quote and deposit URI as is.
        CurrencyInputType.Crypto ->
            MayaCurrencyList[asset]?.swapAmountScale ?: MAX_CRYPTO_DECIMALS
    }

    /** Format a converted value into a plain decimal string (no grouping/symbol, no exponent). */
    private fun formatForDisplay(
        value: BigDecimal,
        type: CurrencyInputType,
        asset: String = _uiState.value.asset
    ): String {
        if (value.signum() == 0) return "0"
        // Crypto rounds DOWN so what's displayed equals the quantized amount the order will
        // actually register (see onContinueClicked); fiat/DASH conversions are display-only
        // and round half-up as usual.
        val mode = if (type == CurrencyInputType.Crypto) RoundingMode.DOWN else RoundingMode.HALF_UP
        return value.setScale(maxDecimalsFor(type, asset), mode).stripTrailingZeros().toPlainString()
    }

    private fun isPositive(amount: String): Boolean =
        amount.toBigDecimalOrNull()?.let { it.signum() > 0 } ?: false

    /** Protocol maximum (Constants.MAX_MONEY) in whole DASH. Read fresh so it tracks the network. */
    private fun maxMoneyDash(): BigDecimal = BigDecimal(Constants.MAX_MONEY.toPlainString())

    private fun buildCurrencyCodes(fiat: String, assetCode: String): List<String> {
        val codes = mutableListOf(fiat, DASH_CURRENCY_CODE)
        if (assetCode.isNotBlank() && !codes.contains(assetCode)) {
            codes.add(assetCode)
        }
        return codes
    }

    companion object {
        private val log = LoggerFactory.getLogger(DEXEnterAmountViewModel::class.java)

        // SavedStateHandle keys for restoring the entered amount after process death.
        private const val KEY_AMOUNT = "dex_enter_amount"
        private const val KEY_ASSET = "dex_enter_amount_asset"

        private const val MAX_FIAT_DECIMALS = 2
        private const val MAX_CRYPTO_DECIMALS = 8

        // Working precision for the cross-currency conversions inside [Amount]. Must comfortably
        // exceed MAX_CRYPTO_DECIMALS so the displayed (rounded) value isn't itself rounding noise.
        private const val CALC_SCALE = 16
    }
}
