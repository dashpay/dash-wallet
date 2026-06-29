package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.integrations.ExchangeIntegration
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import org.dash.wallet.common.ui.address_input.AddressSource
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.dash.wallet.integrations.maya.model.SwapQuote
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MayaAddressInputViewModel @Inject constructor(
    private val exchangeIntegrationProvider: ExchangeIntegrationProvider,
    private val swapProvider: SwapProvider
) : ViewModel() {
    lateinit var asset: String
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
        return swapProvider.getDefaultSwapQuote(asset, destinationAddress)
    }
}
