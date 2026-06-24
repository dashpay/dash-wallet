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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.dash.wallet.integrations.maya.swapkit.SwapKitConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * UI state for the DashDEX buy "Send {COIN} to this address" screen (Figma node 35042-51682).
 *
 * After the user has chosen the asset to buy, entered an amount and supplied a refund address,
 * this screen shows the deposit address (+ QR) the user must send the crypto to. SwapKit converts
 * the received crypto to DASH and deposits it in the user's DashPay wallet.
 *
 * The deposit [address] (and the [uri] that the QR encodes) is produced by a SwapKit buy-swap
 * call (see [DEXReceiveViewModel.loadDepositAddress]); the screen renders a loading state until it
 * resolves, or [errorMessage] if it fails.
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
class DEXReceiveViewModel @Inject constructor(
    private val swapProvider: SwapProvider,
    private val walletDataProvider: WalletDataProvider,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(DEXReceiveViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(DEXReceiveUIState())
    val uiState: StateFlow<DEXReceiveUIState> = _uiState.asStateFlow()

    // Inputs gathered on the previous steps, held for the buy-swap call in loadDepositAddress().
    private var asset: String = ""
    private var refundAddress: String = ""
    private var sellAmount: String = ""

    /**
     * Seed the screen with the inputs gathered on the previous steps. [currencyCode] (e.g. "BTC")
     * is the display code shown in the heading; [asset] (e.g. "BTC.BTC") is the SwapKit identifier;
     * [sellAmount] is the human-unit amount of the crypto being sent in (from the shared
     * DEXEnterAmountViewModel); [refundAddress] is the address funds are returned to if the swap
     * fails (also reported to SwapKit as the source/refund address for NEAR-route buys).
     */
    fun setArguments(asset: String, currencyCode: String, refundAddress: String, sellAmount: String) {
        this.asset = asset
        this.refundAddress = refundAddress
        this.sellAmount = sellAmount
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
     * Resolve the deposit (inbound) address for the buy swap (crypto -> DASH) via SwapKit
     * `/v3/quote` + `/v3/swap`: sell the chosen [asset] for DASH, with the converted DASH sent to
     * the wallet's current receive address and [refundAddress] reported as the source/refund
     * address. The resulting inbound address is where the user sends the crypto.
     */
    fun loadDepositAddress() {
        if (asset.isBlank() || sellAmount.isBlank() || refundAddress.isBlank()) {
            log.warn("loadDepositAddress: missing inputs asset={} sellAmount={} refund(blank)={}",
                asset, sellAmount, refundAddress.isBlank())
            _uiState.update { it.copy(isLoading = false, errorMessage = "Missing swap details") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val destinationAddress = walletDataProvider.currentReceiveAddress().toBase58()
            transactionMetadataProvider.markAddressWithTaxCategory(
                destinationAddress.toString(),
                false,
                TaxCategory.Income,
                ServiceName.Swapkit
            )
            when (val result = swapProvider.createBuyOrder(asset, sellAmount, destinationAddress, refundAddress)) {
                is ResponseResource.Success -> {
                    val order = result.value
                    _uiState.update {
                        it.copy(
                            address = order.depositAddress,
                            uri = buildUri(order.depositAddress, order.sellAmount),
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
                is ResponseResource.Failure -> {
                    log.error("createBuyOrder failed: {}", result.throwable.message)
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = result.throwable.message)
                    }
                }
            }
        }
    }

    /**
     * Build the BIP-21-style payment URI for the QR / URI row: `<scheme>:<address>?amount=<amount>`.
     *
     * The scheme (the "coin name", e.g. "bitcoin", "litecoin", "ethereum") is taken from the
     * payment processor registered for the chosen [asset] — its [PaymentIntentParser.uriPrefix].
     * The [amount] is the human-unit decimal of the crypto the user is sending in.
     *
     * Falls back to the plain [address] when the asset has no registered processor or the amount is
     * blank — every wallet can still scan a bare address.
     */
    private fun buildUri(address: String, amount: String): String {
        val scheme = MayaCurrencyList[asset]?.paymentIntentParser?.uriPrefix
        if (scheme.isNullOrBlank()) {
            return address
        }
        val base = "$scheme:$address"
        return if (amount.isNotBlank()) "$base?amount=$amount" else base
    }
}