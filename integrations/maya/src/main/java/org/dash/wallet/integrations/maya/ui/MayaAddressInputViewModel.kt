package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.integrations.ExchangeIntegration
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import org.dash.wallet.common.ui.address_input.AddressSource
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MayaAddressInputViewModel @Inject constructor(
    private val exchangeIntegrationProvider: ExchangeIntegrationProvider
) : ViewModel() {
    private val inputCurrency = MutableStateFlow<String?>(null)
    private val _addressSources = MutableStateFlow(listOf<AddressSource>())
    val addressSources: Flow<List<AddressSource>>
        get() = _addressSources

    init {

//        inputCurrency
//            .filterNotNull()
//            .mapLatest(exchangeIntegrationProvider::getDepositAddresses)
//            .onEach {
//                refreshAddressSources(it)
//            }
//            .launchIn(viewModelScope)
    }

    private fun refreshAddressSources(it: List<ExchangeIntegration>) {
        val sources = it.map { integration ->
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
}
