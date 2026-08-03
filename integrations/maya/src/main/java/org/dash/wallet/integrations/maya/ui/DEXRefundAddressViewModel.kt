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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Result of a successful buy-order creation, handed to the Fragment so it can navigate to the
 * receive screen carrying the SwapKit deposit address (and the sell amount used to build its URI).
 * [memo] is the chain memo/tag some chains require alongside the deposit — the receive screen must
 * surface it, or a deposit sent without it can't be attributed to the swap (funds lost).
 */
data class DEXRefundOrderResult(
    val depositAddress: String,
    val sellAmount: String,
    val memo: String?
)

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
    // The asset being bought (e.g. "BTC.BTC") and its display code (e.g. "BTC", or
    // "USDC (Ethereum)" for a token), passed in from the enter-amount step. The code is used in
    // the description and the "not a valid X address" error copy.
    val asset: String = "",
    val currencyCode: String = "",
    // The current refund address text shown in the field.
    val address: String = "",
    // True once an address has been entered (Continue is enabled on non-blank input).
    val continueEnabled: Boolean = false,
    // When non-null, the field is in an error state; the value is the currency code to format
    // into R.string.not_valid_address. Cleared as soon as the user edits the address.
    val errorCurrencyCode: String? = null,
    // True while the buy order is being created with SwapKit (Continue shows a spinner).
    val isSubmitting: Boolean = false,
    // Non-null when creating the order failed: a friendly, localized message resource mapped from the
    // provider error by [SwapProvider.errorMessageRes]. Resolved by the screen with [currencyCode]
    // as the format arg.
    @StringRes val orderErrorRes: Int? = null,
    // False when the device has no network connection; the screen shows a no-connection toast.
    val isOnline: Boolean = true
) {
    val hasError: Boolean get() = errorCurrencyCode != null
}

@HiltViewModel
class DEXRefundAddressViewModel @Inject constructor(
    private val swapProvider: SwapProvider,
    private val walletDataProvider: WalletDataProvider,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    networkState: NetworkStateInt,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(DEXRefundAddressUIState())
    val uiState: StateFlow<DEXRefundAddressUIState> = _uiState.asStateFlow()

    // Fired with the created order once SwapKit accepts the swap; the Fragment observes this to
    // navigate to the receive screen. Carries the deposit address so receive doesn't re-create it.
    val onOrderCreated = SingleLiveEvent<DEXRefundOrderResult>()

    init {
        // Mirror connectivity into the UI state so the screen can show the no-connection toast,
        // matching the coin picker (see MayaViewModel).
        networkState.isConnected
            .onEach { online -> _uiState.update { it.copy(isOnline = online) } }
            .launchIn(viewModelScope)
    }

    /** Seed the screen with the asset/currency chosen on the previous (enter-amount) step. */
    fun setArguments(asset: String, currencyCode: String) {
        // Called from the Fragment's onCreateView, which re-runs on back-navigation and after
        // process death. While the ViewModel is alive keep an address the user already typed for the
        // same asset; otherwise restore one persisted before process death (asset-gated), falling
        // back to empty for a genuinely new entry.
        if (_uiState.value.asset == asset && _uiState.value.address.isNotBlank()) {
            return
        }
        val restored = savedStateHandle.get<String>(KEY_ADDRESS)
            ?.takeIf { savedStateHandle.get<String>(KEY_ASSET) == asset }
            .orEmpty()
        // Qualify tokens with their host network (e.g. "ETH (Ethereum)") so the user knows which
        // chain the refund address must be valid for; native L1 coins show just the code.
        val network = MayaCurrencyList.networkName(asset)
        val displayCode = if (network != null) "$currencyCode ($network)" else currencyCode
        _uiState.update {
            it.copy(
                asset = asset,
                currencyCode = displayCode,
                address = restored,
                continueEnabled = restored.isNotBlank(),
                errorCurrencyCode = null,
                isSubmitting = false,
                orderErrorRes = null
            )
        }
        persistAddress()
    }

    /** Update the entered address (typing / paste / scan), clearing any prior error state. */
    fun onAddressChanged(address: String) {
        // Address entry is disabled while offline; the field enforces this, but paste/scan are
        // triggered from the Fragment, so guard here too.
        if (!_uiState.value.isOnline) return
        _uiState.update {
            it.copy(
                address = address,
                continueEnabled = address.isNotBlank(),
                errorCurrencyCode = null,
                orderErrorRes = null
            )
        }
        persistAddress()
    }

    /**
     * Canonicalizes a scanned/pasted address for display: the all-uppercase bech32 form QR
     * codes use becomes lowercase; case-significant forms (Base58, EIP-55) pass through.
     */
    fun normalizeCase(text: String): String {
        val parser = MayaCurrencyList[_uiState.value.asset]?.addressParser
        return parser?.normalizeCase(text) ?: text
    }

    /** Persist the entered address + its asset so it survives process death. */
    private fun persistAddress() {
        savedStateHandle[KEY_ADDRESS] = _uiState.value.address
        savedStateHandle[KEY_ASSET] = _uiState.value.asset
    }

    /**
     * Handle Continue: validate the entered address against the bought asset's chain, then create
     * the buy order with SwapKit for [sellAmount] (the human-unit crypto amount from the enter-amount
     * step). This both validates the swap end-to-end and yields the deposit address, which is handed
     * to the receive screen via [onOrderCreated] — so the receive screen no longer calls createBuyOrder.
     *
     * On an invalid address the inline "not a valid X address" error is shown; on a SwapKit failure
     * [DEXRefundAddressUIState.orderErrorRes] is set. Navigation happens only on success.
     */
    fun submitOrder(sellAmount: String) {
        val state = _uiState.value
        if (state.isSubmitting || !state.isOnline) return

        val validAddress = validateAddress() ?: return // invalid -> inline error already shown

        _uiState.update { it.copy(isSubmitting = true, orderErrorRes = null) }
        viewModelScope.launch {
            // The converted DASH lands in the wallet's current receive address.
            // Deriving the receive address touches the keychain — keep it off the main thread.
            val destinationAddress = withContext(Dispatchers.IO) {
                walletDataProvider.currentReceiveAddressString()
            }
            when (
                val result = swapProvider.createBuyOrder(state.asset, sellAmount, destinationAddress, validAddress)
            ) {
                is ResponseResource.Success -> {
                    val order = result.value
                    // Mark the receive address as income for tax reporting only once the order
                    // actually exists — tagging before createBuyOrder would leave stray metadata
                    // on a failed attempt.
                    transactionMetadataProvider.markAddressWithTaxCategory(
                        destinationAddress,
                        false,
                        TaxCategory.Income,
                        ServiceName.Swapkit
                    )
                    _uiState.update { it.copy(isSubmitting = false, orderErrorRes = null) }
                    onOrderCreated.postValue(
                        DEXRefundOrderResult(
                            depositAddress = order.depositAddress,
                            sellAmount = order.sellAmount,
                            memo = order.memo
                        )
                    )
                }
                is ResponseResource.Failure -> {
                    // Log the raw SwapKit message for diagnostics; surface a friendly, localized one.
                    log.error("createBuyOrder failed: {}", result.throwable.message)
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            orderErrorRes = swapProvider.errorMessageRes(result.throwable.message)
                        )
                    }
                }
            }
        }
    }

    /**
     * Validate the entered address against the bought asset's chain. Returns the trimmed,
     * valid address on success; null (and sets an inline error) on failure or unknown asset.
     */
    private fun validateAddress(): String? {
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

    companion object {
        private val log = LoggerFactory.getLogger(DEXRefundAddressViewModel::class.java)

        // SavedStateHandle keys for restoring the entered refund address after process death.
        private const val KEY_ADDRESS = "dex_refund_address"
        private const val KEY_ASSET = "dex_refund_address_asset"
    }
}
