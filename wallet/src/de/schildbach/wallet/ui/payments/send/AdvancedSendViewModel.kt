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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.DestinationParse
import de.schildbach.wallet.service.platform.sdk.ExternalSendService
import de.schildbach.wallet.service.platform.sdk.detectSendDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.DashPaymentIntentParser
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

/** @property showSendToUsername the user has an identity, so they can pick a contact. */
data class AdvancedSendUIState(val showSendToUsername: Boolean = false)

/** What a scanned QR code leads to on the Advanced Send tab. */
sealed class AdvancedScanAction {
    /** A DashConnect `dash-key:` / `dash-st:` code, for the Connections screen. */
    data class DashConnect(val input: String) : AdvancedScanAction()

    /**
     * A payment request the Basic Send tab handles and that is not a plain destination — a
     * BIP70 `dash:?r=` link, an AnyPay `pay:?r=` link, a serialized `DASH:-` request, or any
     * other `dash:` URI the external flow cannot use: [DashPaymentIntentParser] →
     * `SendCoinsActivity`, exactly as the Basic Send tab.
     */
    data class PaymentRequest(val input: String) : AdvancedScanAction()

    /** A destination of this network: the external send flow, pre-filled with [input]. */
    data class ExternalSend(val input: String) : AdvancedScanAction()

    /** Not usable; [wrongNetwork] = an address of another Dash network. */
    data class Error(val wrongNetwork: Boolean) : AdvancedScanAction()
}

/** The Send tab of the Advanced payments screen (Figma 6894:49688). */
@HiltViewModel
class AdvancedSendViewModel @Inject constructor(
    service: ExternalSendService,
    private val analytics: AnalyticsService
) : ViewModel() {

    companion object {
        /**
         * Route a scanned [input], in order: a DashConnect code ([isDashConnect], only when
         * [supportsConnect]); a plain destination of this network, including a `dash:` URI
         * without `r=` ([detectSendDestination] Valid) → the external flow; a wrong-network
         * one → that error; anything else the Basic Send tab's [DashPaymentIntentParser] owns
         * (`dash:`/`DASH:-` URIs and AnyPay `pay:` links that are not a plain destination, e.g.
         * BIP70 `r=` invoices) → the Basic path; everything else → invalid QR.
         */
        fun scanActionFor(
            input: String,
            network: AddressNetwork,
            supportsConnect: Boolean,
            isDashConnect: (String) -> Boolean
        ): AdvancedScanAction {
            if (supportsConnect && isDashConnect(input)) return AdvancedScanAction.DashConnect(input)
            val parse = detectSendDestination(input, network)
            return when {
                parse is DestinationParse.Valid -> AdvancedScanAction.ExternalSend(input)
                // Never carries `r=` (an invoice link is Invalid there), so no invoice is lost.
                parse is DestinationParse.WrongNetwork -> AdvancedScanAction.Error(wrongNetwork = true)
                isBasicPaymentRequest(input) -> AdvancedScanAction.PaymentRequest(input)
                else -> AdvancedScanAction.Error(wrongNetwork = false)
            }
        }

        /**
         * The schemes [DashPaymentIntentParser.parse] (`supportAnypayUrls = true`) handles
         * besides a bare address: `pay:` (AnyPay), `DASH:-` (serialized BIP70 request) and
         * `dash:` URIs. It decides itself (and reports its own error) for these.
         */
        private fun isBasicPaymentRequest(input: String): Boolean =
            input.startsWith("${Constants.ANYPAY_SCHEME}:") ||
                input.startsWith("${Constants.DASH_SCHEME}:", ignoreCase = true)
    }

    private val _uiState = MutableStateFlow(AdvancedSendUIState())
    val uiState: StateFlow<AdvancedSendUIState> = _uiState.asStateFlow()

    init {
        service.observeHasIdentity()
            .onEach { has -> _uiState.update { it.copy(showSendToUsername = has == true) } }
            .launchIn(viewModelScope)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }
}
