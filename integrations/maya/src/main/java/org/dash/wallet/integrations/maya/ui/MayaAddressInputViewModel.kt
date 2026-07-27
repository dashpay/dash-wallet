package org.dash.wallet.integrations.maya.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.integrations.ExchangeIntegration
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.ui.address_input.AddressSource
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.SwapQuote
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MayaAddressInputViewModel @Inject constructor(
    private val exchangeIntegrationProvider: ExchangeIntegrationProvider,
    private val swapProvider: SwapProvider,
    walletDataProvider: WalletDataProvider
) : ViewModel() {
    companion object {
        // Indicative sell amount for the bootstrap quote: 1 DASH in base units.
        private const val DEFAULT_QUOTE_VALUE = 1_0000_0000L

        // How many times the default amount is doubled when a route rejects it as
        // below its minimum (1 -> 2 -> 4 DASH).
        private const val MAX_QUOTE_DOUBLINGS = 2
    }

    lateinit var asset: String

    private val dashAddressParser = AddressParser.getDashAddressParser(walletDataProvider.networkParameters)

    /**
     * True when the input is a checksum-valid DASH address on this wallet's network. A Dash
     * address is never a valid sell-swap destination (the target chain is always non-Dash),
     * but several target chains validate with permissive lexical patterns that a Dash
     * address also satisfies — e.g. Solana's 32-44 char Base58 range — letting the mistake
     * through to a conversion that can only fail (MO-969). Checked as an explicit reject at
     * the continue gate so it covers every asset's parser at once.
     */
    fun isDashAddress(input: String): Boolean = dashAddressParser.exactMatch(input.trim())

    // Source of truth for the inline validation error and the address it was shown for:
    // the Compose UIState lives in the fragment and is recreated with the view, so they
    // are kept here to survive configuration changes. lastSeenAddress lets the fragment
    // distinguish a real address edit (which invalidates the error) from the replay of
    // the persisted address right after recreation.
    var inlineErrorMessage: String? = null
    var lastSeenAddress: String? = null

    private val inputCurrency = MutableStateFlow<String?>(null)
    private val _addressSources = MutableStateFlow(listOf<AddressSource>())
    val addressSources: Flow<List<AddressSource>>
        get() = _addressSources.asStateFlow()

    private fun refreshAddressSources(integrations: List<ExchangeIntegration>) {
        // The selected [asset] (e.g. "TRON.USDT") pins the destination network. An
        // exchange such as Coinbase may only support some networks for a coin (e.g.
        // ERC-20 USDT, not TRON.USDT) and hand back a deposit address on the wrong
        // network. Sending the swap output there would lose funds, so drop any
        // connected source whose address doesn't validate against this asset's own
        // parser. Sources without an address yet (not connected) are kept so the user
        // can still connect.
        val addressParser = if (::asset.isInitialized) MayaCurrencyList[asset]?.addressParser else null
        val sources = integrations
            .filter { integration ->
                val address = integration.address
                address == null || addressParser == null || addressParser.exactMatch(address.trim())
            }
            .map { integration ->
                AddressSource(
                    integration.id,
                    integration.name,
                    integration.iconId,
                    integration.address,
                    integration.currency
                )
            }
        _addressSources.value = sources
    }

    fun setCurrency(currency: String) {
        inputCurrency.value = currency
    }

    fun refreshAddressSources() {
        inputCurrency.value?.let {
            viewModelScope.launch {
                refreshAddressSources(exchangeIntegrationProvider.getDepositAddresses(it))
            }
        }
    }

    suspend fun getDefaultQuote(): SwapQuote? {
        return swapProvider.getDefaultSwapQuote(asset)
    }

    suspend fun getDefaultQuote(destinationAddress: String): SwapQuote? {
        var value = DEFAULT_QUOTE_VALUE
        var quote = swapProvider.getDefaultSwapQuote(asset, destinationAddress, value)
        // Some routes have a minimum above the default indicative amount, which the
        // backend reports as an amount-too-low error. Double the amount and retry —
        // at most [MAX_QUOTE_DOUBLINGS] times — before surfacing the error. Other
        // errors (bad address, network failure) won't be fixed by a bigger amount,
        // so they are returned as is.
        repeat(MAX_QUOTE_DOUBLINGS) {
            val error = quote?.error
            if (error == null || !swapProvider.isAmountTooLowError(error)) {
                return quote
            }
            value *= 2
            quote = swapProvider.getDefaultSwapQuote(asset, destinationAddress, value)
        }
        return quote
    }

    /** Friendly message resource for a quote error, mapped by whichever backend is active. */
    @StringRes
    fun errorMessageRes(error: String?): Int = swapProvider.errorMessageRes(error)
}
