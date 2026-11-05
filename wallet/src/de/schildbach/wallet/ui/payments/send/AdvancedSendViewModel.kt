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
import org.dash.wallet.common.payments.parsers.PaymentURI
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

/** @property showSendToUsername the user has an identity, so they can pick a contact. */
data class AdvancedSendUIState(val showSendToUsername: Boolean = false)

/** What a scanned QR code leads to on the Advanced Send tab. */
sealed class AdvancedScanAction {
    /** A DashConnect `dash-key:` / `dash-st:` code, for the Connections screen. */
    data class DashConnect(val input: String) : AdvancedScanAction()

    /** A `dash:` URI with a BIP70 payment request (`r=`): the Basic invoice flow, as today. */
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
         * Route a scanned [input]. [isDashConnect] is only consulted when [supportsConnect]; a
         * BIP70 `dash:` link keeps the merchant invoice flow, everything else goes through
         * [detectSendDestination].
         */
        fun scanActionFor(
            input: String,
            network: AddressNetwork,
            supportsConnect: Boolean,
            isDashConnect: (String) -> Boolean
        ): AdvancedScanAction {
            if (supportsConnect && isDashConnect(input)) return AdvancedScanAction.DashConnect(input)
            if (isPaymentRequestUri(input)) return AdvancedScanAction.PaymentRequest(input)
            return when (detectSendDestination(input, network)) {
                is DestinationParse.Valid -> AdvancedScanAction.ExternalSend(input)
                DestinationParse.WrongNetwork -> AdvancedScanAction.Error(wrongNetwork = true)
                DestinationParse.Empty, DestinationParse.Invalid -> AdvancedScanAction.Error(wrongNetwork = false)
            }
        }

        private fun isPaymentRequestUri(input: String): Boolean {
            val trimmed = input.trim()
            if (!trimmed.startsWith("${AddressNetwork.DASH_SCHEME}:", ignoreCase = true)) return false
            return try {
                PaymentURI(null, trimmed).paymentRequestUrl != null
            } catch (e: PaymentURI.ParseException) {
                false
            }
        }
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
