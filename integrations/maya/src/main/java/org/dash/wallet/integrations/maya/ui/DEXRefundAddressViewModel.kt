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
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import javax.inject.Inject

/**
 * UI state for the DashDEX buy "Enter refund address" screen (Figma node 35199-9405).
 *
 * The user types (or pastes / scans) an address that funds will be returned to if the swap
 * fails. The address must be valid for the chain of the asset being bought ([asset], e.g.
 * "BTC.BTC"); validation runs against that asset's [org.dash.wallet.common.payments.parsers.AddressParser]
 * on Continue. [continueEnabled] is derived purely from the field being non-empty; the parser
 * check happens when Continue is pressed and surfaces [errorMessageArg] for an inline error.
 */
data class DEXRefundAddressUIState(
    // The asset being bought (e.g. "BTC.BTC") and its display code (e.g. "BTC"), passed in
    // from the enter-amount step. The code is used in the "not a valid X address" error copy.
    val asset: String = "",
    val currencyCode: String = "",
    // The current refund address text shown in the field.
    val address: String = "",
    // True once an address has been entered (Continue is enabled on non-blank input).
    val continueEnabled: Boolean = false,
    // When non-null, the field is in an error state; the value is the currency code to format
    // into R.string.not_valid_address. Cleared as soon as the user edits the address.
    val errorCurrencyCode: String? = null
) {
    val hasError: Boolean get() = errorCurrencyCode != null
}

@HiltViewModel
class DEXRefundAddressViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(DEXRefundAddressUIState())
    val uiState: StateFlow<DEXRefundAddressUIState> = _uiState.asStateFlow()

    /** Seed the screen with the asset/currency chosen on the previous (enter-amount) step. */
    fun setArguments(asset: String, currencyCode: String) {
        _uiState.update {
            it.copy(
                asset = asset,
                currencyCode = currencyCode,
                address = "",
                continueEnabled = false,
                errorCurrencyCode = null
            )
        }
    }

    /** Update the entered address (typing / paste / scan), clearing any prior error state. */
    fun onAddressChanged(address: String) {
        _uiState.update {
            it.copy(
                address = address,
                continueEnabled = address.isNotBlank(),
                errorCurrencyCode = null
            )
        }
    }

    /**
     * Validate the entered address against the bought asset's chain. Returns the trimmed,
     * valid address on success; null (and sets an inline error) on failure or unknown asset.
     */
    fun validateAddress(): String? {
        val state = _uiState.value
        val candidate = state.address.trim()
        val parser = MayaCurrencyList[state.asset]?.addressParser

        return if (parser != null && candidate.isNotEmpty() && parser.exactMatch(candidate)) {
            candidate
        } else {
            _uiState.update { it.copy(errorCurrencyCode = it.currencyCode) }
            null
        }
    }
}