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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.CurrencyInputType
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
    // Fiat ISO code the amount is entered in (primary input), e.g. "USD".
    val fiatCurrencyCode: String = "USD",
    // Display order for the EnterAmount currency picker: fiat (primary), DASH, asset.
    val currencyCodes: List<String> = listOf("USD", DASH_CURRENCY_CODE),
    val selectedCurrencyIndex: Int = 0,
    // Raw entered amount string, as shown in the primary amount slot, in the selected currency.
    val amount: String = "0",
    val continueEnabled: Boolean = false
)

@HiltViewModel
class DEXEnterAmountViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(DEXEnterAmountUIState())
    val uiState: StateFlow<DEXEnterAmountUIState> = _uiState.asStateFlow()

    // Tracks the entered value across fiat / DASH / the bought asset at once. Typing anchors the
    // currently-selected currency; the other two are recomputed from the exchange rates so that
    // switching currency shows the converted amount.
    private var amount = Amount()

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
        _uiState.update {
            it.copy(
                asset = asset,
                assetCurrencyCode = assetCurrencyCode,
                fiatCurrencyCode = fiatCurrencyCode,
                currencyCodes = buildCurrencyCodes(fiatCurrencyCode, assetCurrencyCode),
                selectedCurrencyIndex = 0,
                amount = "0",
                continueEnabled = false
            )
        }
    }

    /** Handle a numeric-keyboard key ("0"–"9", ".", "back", "back_long"). */
    fun onKeyInput(key: String) {
        _uiState.update { state ->
            val type = currencyTypeFor(state, state.selectedCurrencyIndex)
            val updated = processAmountKeyInput(state.amount, key, maxDecimalsFor(type))
            amount.setAnchored(type, updated.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            state.copy(
                amount = updated,
                continueEnabled = isPositive(updated)
            )
        }
    }

    /** Switch the active display currency, re-deriving the shown amount from the tracked value. */
    fun onCurrencySelected(index: Int) {
        _uiState.update { state ->
            val newIndex = index.coerceIn(0, state.currencyCodes.lastIndex.coerceAtLeast(0))
            val type = currencyTypeFor(state, newIndex)
            val value = amount.getValue(type)
            amount.anchoredType = type
            state.copy(
                selectedCurrencyIndex = newIndex,
                amount = formatForDisplay(value, maxDecimalsFor(type)),
                continueEnabled = value.signum() > 0
            )
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

    private fun maxDecimalsFor(type: CurrencyInputType): Int =
        if (type == CurrencyInputType.Fiat) MAX_FIAT_DECIMALS else MAX_CRYPTO_DECIMALS

    /** Format a converted value into a plain decimal string (no grouping/symbol, no exponent). */
    private fun formatForDisplay(value: BigDecimal, decimals: Int): String {
        if (value.signum() == 0) return "0"
        return value.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun isPositive(amount: String): Boolean =
        amount.toBigDecimalOrNull()?.let { it.signum() > 0 } ?: false

    private fun buildCurrencyCodes(fiat: String, assetCode: String): List<String> {
        val codes = mutableListOf(fiat, DASH_CURRENCY_CODE)
        if (assetCode.isNotBlank() && !codes.contains(assetCode)) {
            codes.add(assetCode)
        }
        return codes
    }

    companion object {
        private const val MAX_FIAT_DECIMALS = 2
        private const val MAX_CRYPTO_DECIMALS = 8

        // Working precision for the cross-currency conversions inside [Amount]. Must comfortably
        // exceed MAX_CRYPTO_DECIMALS so the displayed (rounded) value isn't itself rounding noise.
        private const val CALC_SCALE = 16
    }
}