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
import javax.inject.Inject

/**
 * UI state for the DashDEX buy "Send {COIN} to this address" screen (Figma node 35042-51682).
 *
 * After the user has chosen the asset to buy, entered an amount and supplied a refund address,
 * this screen shows the deposit address (+ QR) the user must send the crypto to. SwapKit converts
 * the received crypto to DASH and deposits it in the user's DashPay wallet.
 *
 * The deposit [address] (and the [uri] that the QR encodes) is produced by a SwapKit buy-swap
 * call that does not exist yet (see [DEXReceiveViewModel.loadDepositAddress]); until it lands the
 * screen renders a loading state ([isLoading] = true).
 */
data class DEXReceiveUIState(
    // Display code of the crypto being sent in (e.g. "BTC"), used in the heading.
    val coinCode: String = "",
    // The SwapKit inbound (deposit) address the user must send the crypto to. Empty while loading.
    val address: String = "",
    // The payment URI encoded in the QR and shown in the URI row. Falls back to [address] when blank.
    val uri: String = "",
    // True until the deposit address has been resolved (currently always true — see loadDepositAddress).
    val isLoading: Boolean = true,
    // Non-null when resolving the deposit address failed; surfaced to the user.
    val errorMessage: String? = null
)

@HiltViewModel
class DEXReceiveViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(DEXReceiveUIState())
    val uiState: StateFlow<DEXReceiveUIState> = _uiState.asStateFlow()

    // The asset being bought (e.g. "BTC.BTC") and the refund address entered on the previous step.
    // Held for the (not-yet-implemented) buy-swap call in loadDepositAddress().
    private var asset: String = ""
    private var refundAddress: String = ""

    /**
     * Seed the screen with the inputs gathered on the previous steps. [currencyCode] (e.g. "BTC")
     * is the display code shown in the heading; [asset] (e.g. "BTC.BTC") is the SwapKit identifier
     * and [refundAddress] is the address funds are returned to if the swap fails (also used by
     * SwapKit as the source/refund address for NEAR-route buys).
     */
    fun setArguments(asset: String, currencyCode: String, refundAddress: String) {
        this.asset = asset
        this.refundAddress = refundAddress
        _uiState.update {
            it.copy(
                coinCode = currencyCode,
                address = "",
                uri = "",
                isLoading = true,
                errorMessage = null
            )
        }
    }

    /**
     * Resolve the deposit (inbound) address for the buy swap.
     *
     * TODO(buy backend): the buy (crypto -> DASH) swap call does not exist yet. The real
     * implementation must call a new SwapProvider buy-swap method that performs SwapKit
     * `/v3/quote` + `/v3/swap` with the direction of [SwapKitApiAggregator.getSwapInfo] flipped:
     *   - sellAsset        = [asset] (the chosen crypto, e.g. "BTC.BTC")
     *   - buyAsset         = SwapKitConstants.DASH_ASSET
     *   - sellAmount       = the entered amount in the crypto, read from the shared, nav-graph-scoped
     *                        DEXEnterAmountViewModel.enteredAmount() (Amount.crypto)
     *   - destinationAddress = the user's DASH receive address (where the converted DASH lands)
     *   - sourceAddress    = [refundAddress] (SwapKit uses this for NEAR-route refunds)
     * Then read back swap.inboundAddress (+ memo) and:
     *   - set uiState.address = swap.inboundAddress
     *   - set uiState.uri     = buildUri(coinCode, swap.inboundAddress, memo/amount)
     *   - set uiState.isLoading = false
     * On failure set uiState.errorMessage and isLoading = false.
     *
     * For now this only keeps the screen in its loading state — no network call, no fabricated
     * address.
     */
    fun loadDepositAddress() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        // Intentionally left as a loading state until the buy-swap backend exists (see TODO above).
    }

    /**
     * Build the payment URI for the QR / URI row from the coin and deposit address.
     *
     * TODO(buy backend): derive the real per-currency BIP-21-style scheme + amount/memo params
     * (e.g. "bitcoin:<addr>?amount=...") once the buy-swap response (inbound address + memo) is
     * available. For now this is unused (address is never set) and returns the plain address.
     */
    @Suppress("unused")
    private fun buildUri(address: String): String = address
}