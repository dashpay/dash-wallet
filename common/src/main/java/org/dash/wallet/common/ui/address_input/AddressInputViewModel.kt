/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.common.ui.address_input

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.payments.parsers.PaymentParsers
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import javax.inject.Inject

data class AddressInputUIState(
    val hasClipboardText: Boolean = false,
    val clipboardText: String = "",
    val addressRanges: List<IntRange> = listOf(),
    val addressInput: String = ""
)

data class AddressInputResult(
    val valid: Boolean = false,
    val addressInput: String = "",
    val paymentIntent: PaymentIntent? = null,
    val currency: String = Constants.DASH_CURRENCY
)

@HiltViewModel
class AddressInputViewModel @Inject constructor(
    private val clipboardManager: ClipboardManager,
    private val analyticsService: AnalyticsService,
    walletDataProvider: WalletDataProvider
): ViewModel() {
    lateinit var paymentParsers: PaymentParsers
    var currency: String = Constants.DASH_CURRENCY
    val addressSources = arrayListOf<AddressSource>()

    private val _uiState = MutableStateFlow(AddressInputUIState())
    val uiState: StateFlow<AddressInputUIState> = _uiState.asStateFlow()

    private var paymentIntent: PaymentIntent? = null
    private var _addressResult = AddressInputResult()
    val addressResult: AddressInputResult
        get() = _addressResult

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        _uiState.value = _uiState.value.copy(hasClipboardText = hasClipboardInput())
    }

    init {
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        _uiState.value = _uiState.value.copy(hasClipboardText = hasClipboardInput())
    }

    fun showClipboardContent() {
        val text = getClipboardInput()
        val addressRanges = paymentParsers.getAddressParser(currency)!!.findAll(text)
        _uiState.value = _uiState.value.copy(clipboardText = text, addressRanges = addressRanges)
        analyticsService.logEvent(AnalyticsConstants.AddressInput.SHOW_CLIPBOARD, mapOf())
    }

    fun setInput(text: String) {
        _uiState.value = _uiState.value.copy(addressInput = text)
        analyticsService.logEvent(AnalyticsConstants.AddressInput.ADDRESS_TAP, mapOf())
    }

    suspend fun parsePaymentIntent(input: String) {
        paymentIntent = paymentParsers.getPaymentIntentParser(currency)!!.parse(input)
    }

    fun setAddressResult(input: String) {
        _addressResult = AddressInputResult(true, input, paymentIntent, currency)
    }

    private fun hasClipboardInput(): Boolean {
        if (clipboardManager.hasPrimaryClip()) {
            val clipDescription = clipboardManager.primaryClip?.description ?: return false

            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
                clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                return true
            }
        }

        return false
    }

    private fun getClipboardInput(): String {
        if (!clipboardManager.hasPrimaryClip() || clipboardManager.primaryClip == null) {
            return ""
        }

        clipboardManager.primaryClip!!.run {
            return when {
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) -> getItemAt(0).uri?.toString()
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) -> getItemAt(0).text?.toString()
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> getItemAt(0).text?.toString()
                else -> null
            } ?: ""
        }
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    override fun onCleared() {
        super.onCleared()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }
}
