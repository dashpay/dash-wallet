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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * UI state for the DashDEX buy "Send {COIN} to this address" screen (Figma node 35042-51682).
 *
 * After the user has chosen the asset to buy, entered an amount and supplied a refund address,
 * this screen shows the deposit address (+ QR) the user must send the crypto to. SwapKit converts
 * the received crypto to DASH and deposits it in the user's DashPay wallet.
 *
 * The deposit [address] (and the [uri] that the QR encodes) is created on the previous
 * (refund-address) step by the SwapKit buy-swap call and passed in via nav args, so this screen is
 * purely presentational — see [DEXReceiveViewModel.setArguments].
 */
data class DEXReceiveUIState(
    // Display code of the crypto being sent in (e.g. "BTC", or "USDC (Ethereum)" for a token),
    // used in the heading and expiry note.
    val coinCode: String = "",
    // The SwapKit inbound (deposit) address the user must send the crypto to. Empty while loading.
    val address: String = "",
    // The payment URI encoded in the QR and shown in the URI row. Falls back to [address] when blank.
    val uri: String = "",
    // True only briefly before setArguments runs; the deposit address arrives ready via nav args.
    val isLoading: Boolean = true,
    // Non-null only in the defensive case where no deposit address was passed in; a friendly,
    // localized message. Kept as a resource id (not a String) so the ViewModel stays Context-free.
    @StringRes val errorMessageRes: Int? = null,
    // False when the device has no network connection; the screen shows a no-connection toast.
    val isOnline: Boolean = true
)

@HiltViewModel
class DEXReceiveViewModel @Inject constructor(
    networkState: NetworkStateInt
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(DEXReceiveViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(DEXReceiveUIState())
    val uiState: StateFlow<DEXReceiveUIState> = _uiState.asStateFlow()

    init {
        // Mirror connectivity into the UI state so the screen can show the no-connection toast,
        // matching the coin picker (see MayaViewModel).
        networkState.isConnected
            .onEach { online -> _uiState.update { it.copy(isOnline = online) } }
            .launchIn(viewModelScope)
    }

    /**
     * Seed the screen with the already-created order. [currencyCode] (e.g. "BTC") is the display
     * code shown in the heading; [asset] (e.g. "BTC.BTC") is the SwapKit identifier used to build
     * the payment URI; [sellAmount] is the human-unit amount of the crypto to send; [depositAddress]
     * is the SwapKit inbound address resolved by the refund step's createBuyOrder call.
     */
    fun setArguments(asset: String, currencyCode: String, sellAmount: String, depositAddress: String) {
        // Qualify tokens with their host network (e.g. "USDC (Ethereum)") so the user can tell which
        // chain to send on; native L1 coins (BTC, ETH, …) show just the code.
        val network = MayaCurrencyList.networkName(asset)
        val displayCode = if (network != null) "$currencyCode ($network)" else currencyCode
        if (depositAddress.isBlank()) {
            // Defensive: the refund step only navigates here with a valid deposit address, so this
            // shouldn't happen — but never show a blank QR if it does.
            log.warn("setArguments: blank deposit address for asset={}", asset)
            _uiState.update {
                it.copy(coinCode = displayCode, isLoading = false, errorMessageRes = R.string.dex_error_generic)
            }
            return
        }
        _uiState.update {
            it.copy(
                coinCode = displayCode,
                address = depositAddress,
                uri = buildUri(asset, depositAddress, sellAmount),
                isLoading = false,
                errorMessageRes = null
            )
        }
    }

    /**
     * Build the payment URI for the QR / URI row.
     */
    private fun buildUri(asset: String, address: String, amount: String): String {
        val addressParser = MayaCurrencyList[asset]
        return addressParser?.getPaymentRequestURI(address, amount) ?: address
    }
}
